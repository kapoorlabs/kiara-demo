param(
    [ValidateSet('All', 'Tune', 'Measure', 'Collect')]
    [string]$Stage = 'All',
    [string[]]$Datasets = @('retail-3788', 'catalog-10000', 'catalog-100000', 'schedule-10000', 'schedule-100000'),
    [int]$Forks = 5,
    [string]$ResultsName = '',
    [string]$ProjectName = '',
    [ValidateSet('tuned', 'generic')]
    [string]$Profile = 'tuned'
)
$ErrorActionPreference = 'Stop'
if (-not $ResultsName) { $ResultsName = if ($Profile -eq 'generic') { 'voltdb-generic-execution' } else { 'voltdb-execution' } }
if (-not $ProjectName) { $ProjectName = if ($Profile -eq 'generic') { 'kiara-voltdb-generic' } else { 'kiara-voltdb-benchmark' } }
if (($Profile -eq 'generic' -and $ResultsName -eq 'voltdb-execution') -or
    ($Profile -eq 'tuned' -and $ResultsName -eq 'voltdb-generic-execution')) { throw 'Profile and reserved result directory disagree' }
$preparePhase = if ($Profile -eq 'generic') { 'prepare' } else { 'tune' }
if ($Forks -lt 1) { throw 'Forks must be positive' }
if ($ResultsName -notmatch '^[a-zA-Z0-9-]+$' -or $ProjectName -notmatch '^[a-z0-9-]+$') { throw 'Invalid result/project name' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $root
$compose = @('compose', '-p', $ProjectName, '-f', 'benchmarks\voltdb\compose.yaml')
$previousResults = $env:BENCHMARK_RESULTS_DIRECTORY
function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE): $Arguments" }
    if ($hash -and (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
        throw 'Executable changed during experiment; do not combine trials'
    }
}
function Start-Database {
    param([switch]$Fresh)
    if ($Fresh) { Invoke-Docker ($compose + @('rm', '--stop', '--force', 'voltdb')) }
    Invoke-Docker ($compose + @('up', '-d', 'voltdb'))
    Invoke-Docker ($compose + @('run', '--rm', '--no-deps', 'worker', '--phase', 'ready'))
}
try {
    $output = Join-Path $root "benchmarks\target\$ResultsName"
    $env:BENCHMARK_RESULTS_DIRECTORY = $output
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $jar = Join-Path $root 'benchmarks\target\kiara-lucene-benchmark-1.0-SNAPSHOT.jar'
    $hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    $archive = Join-Path $output "measured-$($hash.Substring(0,8)).jar"
    if (-not (Test-Path -LiteralPath $archive)) { Copy-Item -LiteralPath $jar -Destination $archive }
    if ($Stage -in @('All', 'Tune', 'Measure')) {
        Invoke-Docker ($compose + @('build', 'worker'))
    }
    if ($Stage -in @('All', 'Tune')) {
        Start-Database -Fresh
        $serverImage = & docker image inspect "$ProjectName-voltdb" --format '{{json .Id}}'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect server image' }
        $workerImage = & docker image inspect "$ProjectName-worker" --format '{{json .Id}}'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect worker image' }
        $dockerInfo = & docker info --format '{{json .}}' | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker platform' }
        $platform = [ordered]@{
            recordedAt = [DateTime]::UtcNow.ToString('o')
            hostCpu = (Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)
            dockerVersion = $dockerInfo.ServerVersion
            dockerKernel = $dockerInfo.KernelVersion
            dockerOS = $dockerInfo.OperatingSystem
            dockerCPUs = $dockerInfo.NCPU
            dockerMemoryBytes = $dockerInfo.MemTotal
            serverImageId = ($serverImage | ConvertFrom-Json)
            workerImageId = ($workerImage | ConvertFrom-Json)
            workerBase = 'eclipse-temurin:21-jdk-jammy@sha256:4e61d0b3471137e239397789661f6adeaf8db9adcfae45916b22f628c8a32dc0'
            serverBase = 'eclipse-temurin:11-jre-jammy@sha256:0c567a8bd671331808b89a96976fabb305d57b123dec4fa62a20bfb89f2b32b9'
            profile = $Profile
            benchmarkJarSha256 = $hash
        } | ConvertTo-Json -Depth 6
        [IO.File]::WriteAllText((Join-Path $output 'platform.json'), $platform, [Text.UTF8Encoding]::new($false))
        foreach ($dataset in $Datasets) {
            Invoke-Docker ($compose + @('run', '--rm', '--no-deps', 'worker',
                '--phase', $preparePhase, '--profile', $Profile, '--engine', 'voltdb', '--dataset', $dataset,
                '--output', "/results/$preparePhase-$dataset.json"))
        }
    }
    if ($Stage -in @('All', 'Measure')) {
        foreach ($dataset in $Datasets) {
            if (-not (Test-Path -LiteralPath (Join-Path $output "$preparePhase-$dataset.json"))) {
                throw "Missing frozen preflight results for $dataset"
            }
            for ($fork = 1; $fork -le $Forks; $fork++) {
                $engines = if ($fork % 2 -eq 1) { @('kiara', 'voltdb') } else { @('voltdb', 'kiara') }
                foreach ($engine in $engines) {
                    if ($engine -eq 'voltdb') { Start-Database -Fresh }
                    else { Invoke-Docker ($compose + @('stop', 'voltdb')) }
                    Invoke-Docker ($compose + @('run', '--rm', '--no-deps', 'worker',
                        '--phase', 'measure', '--profile', $Profile, '--engine', $engine, '--dataset', $dataset, '--fork', "$fork",
                        '--tuning', "/results/$preparePhase-$dataset.json", '--output', "/results/$engine-$dataset-$fork.json"))
                }
            }
        }
    }
    if ($Stage -in @('All', 'Measure', 'Collect')) {
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
        $raw = if ($ResultsName -eq 'voltdb-execution') { 'benchmarks\voltdb-query-execution.json' }
            elseif ($ResultsName -eq 'voltdb-generic-execution') { 'benchmarks\voltdb-untuned-comparison.json' } else { Join-Path $output 'comparison.json' }
        $report = if ($ResultsName -eq 'voltdb-execution') { 'docs\voltdb-query-execution.md' }
            elseif ($ResultsName -eq 'voltdb-generic-execution') { 'docs\voltdb-untuned-comparison.md' } else { Join-Path $output 'comparison.md' }
        & $java -cp benchmarks\target\kiara-lucene-benchmark-1.0-SNAPSHOT.jar `
            com.kapoorlabs.benchmark.VoltExecutionBenchmark --collect $output `
            --datasets ($Datasets -join ',') --forks $Forks --profile $Profile `
            --output $raw --report $report
        if ($LASTEXITCODE -ne 0) { throw 'Collection or report validation failed' }
        if ($Profile -eq 'generic') {
            & node .\benchmarks\voltdb\verify-generic.cjs $raw $report $output
            if ($LASTEXITCODE -ne 0) { throw 'Independent generic VoltDB verification failed' }
        }
    }
} finally {
    try { Invoke-Docker ($compose + @('down')) }
    finally { $env:BENCHMARK_RESULTS_DIRECTORY = $previousResults; Pop-Location }
}
