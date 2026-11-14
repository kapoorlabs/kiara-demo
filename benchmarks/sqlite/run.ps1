param(
    [ValidateSet('All', 'Tune', 'Measure', 'Collect')]
    [string]$Stage = 'All',
    [string[]]$Datasets = @('retail-3788', 'catalog-10000', 'catalog-100000', 'schedule-10000', 'schedule-100000'),
    [int]$Forks = 5,
    [string]$ResultsName = '',
    [ValidateSet('tuned', 'generic')]
    [string]$Profile = 'tuned',
    [ValidateRange(1, 2147483647)]
    [int]$QueryTimeoutMs = 60000
)
$ErrorActionPreference = 'Stop'
if (-not $ResultsName) { $ResultsName = if ($Profile -eq 'generic') { 'sqlite-generic-execution' } else { 'sqlite-execution' } }
if (($Profile -eq 'generic' -and $ResultsName -eq 'sqlite-execution') -or
    ($Profile -eq 'tuned' -and $ResultsName -eq 'sqlite-generic-execution')) { throw 'Profile and reserved results directory disagree' }
$preparePhase = if ($Profile -eq 'generic') { 'prepare' } else { 'tune' }
if ($Forks -lt 1 -or $ResultsName -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid fork count or results name' }
foreach ($dataset in $Datasets) {
    if ($dataset -notmatch '^(retail-3788|catalog-[1-9][0-9]*|schedule-[1-9][0-9]*|wide-[1-9][0-9]*)$') { throw "Invalid dataset: $dataset" }
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $root "benchmarks\target\$ResultsName"
$container = 'kiara-sqlite-' + [Guid]::NewGuid().ToString('N')
$image = "kiara-sqlite-benchmark:$Profile"
function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE): $Arguments" }
}
function Invoke-Worker {
    param([string[]]$Arguments)
    if ($Profile -eq 'generic') { $Arguments += @('--query-timeout-ms', "$QueryTimeoutMs") }
    $previousTimeouts = 0
    do {
        & docker @('run', '--rm', '--name', $container, '--network', 'none', '--cpus', '4',
            '--memory', '8g', '--memory-swap', '8g', '--mount', "type=bind,source=$output,target=/results", $image) @Arguments
        $code = $LASTEXITCODE
        if ($code -eq 124 -and $Profile -eq 'generic') {
            $position = [Array]::IndexOf($Arguments, '--output')
            if ($position -lt 0) { throw 'Timeout without output/checkpoint path' }
            $checkpoint = Join-Path $output (($Arguments[$position + 1] -replace '^/results/', '') + '.checkpoint.json')
            $saved = Get-Content -LiteralPath $checkpoint -Raw | ConvertFrom-Json
            $timeouts = @($saved.observations | Where-Object { $_.query.validation.status -eq 'QUERY_TIMEOUT' }).Count
            if ($timeouts -le $previousTimeouts -or $timeouts -gt 100) { throw 'Deadline restart made no progress' }
            $previousTimeouts = $timeouts
            Write-Output "Restarting fresh worker after recorded deadline ($timeouts timed-out scenarios)"
        } elseif ($code -ne 0) {
            throw "Docker worker failed ($code): $Arguments"
        }
    } while ($code -eq 124)
}
Push-Location $root
try {
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    if ($Stage -in @('All', 'Tune')) {
        Invoke-Docker -Arguments @('build', '-f', 'benchmarks\sqlite\Dockerfile', '-t', $image, 'benchmarks')
        $imageId = & docker image inspect $image --format '{{.Id}}'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect worker image' }
        $info = & docker info --format '{{json .}}' | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker host' }
        $platform = [ordered]@{
            recordedAt = [DateTime]::UtcNow.ToString('o')
            hostCpu = (Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)
            dockerVersion = $info.ServerVersion
            dockerKernel = $info.KernelVersion
            dockerOS = $info.OperatingSystem
            dockerCPUs = $info.NCPU
            dockerMemoryBytes = $info.MemTotal
            workerImageId = $imageId
            workerBase = 'eclipse-temurin:21-jdk-jammy@sha256:4e61d0b3471137e239397789661f6adeaf8db9adcfae45916b22f628c8a32dc0'
            cpuQuota = 4
            memoryBytes = 8589934592
            network = 'none'
            datasets = $Datasets
            forks = $Forks
            profile = $Profile
            queryTimeoutMs = $(if ($Profile -eq 'generic') { $QueryTimeoutMs } else { 0 })
        } | ConvertTo-Json -Depth 6
        [IO.File]::WriteAllText((Join-Path $output 'platform.json'), $platform, [Text.UTF8Encoding]::new($false))
        foreach ($dataset in $Datasets) {
            Invoke-Worker -Arguments @('--phase', $preparePhase, '--profile', $Profile, '--engine', 'sqlite', '--dataset', $dataset,
                '--output', "/results/$preparePhase-$dataset.json")
        }
    }
    if ($Stage -in @('All', 'Measure')) {
        $platform = Get-Content -LiteralPath (Join-Path $output 'platform.json') -Raw | ConvertFrom-Json
        if ($platform.profile -ne $Profile) { throw 'Recorded profile differs from requested profile' }
        if ($Profile -eq 'generic' -and $platform.queryTimeoutMs -ne $QueryTimeoutMs) { throw 'Recorded deadline differs from requested deadline' }
        $currentImage = & docker image inspect $image --format '{{.Id}}'
        if ($LASTEXITCODE -ne 0 -or $currentImage -ne $platform.workerImageId) { throw 'Frozen worker image changed or is missing' }
        foreach ($dataset in $Datasets) {
            if (-not (Test-Path -LiteralPath (Join-Path $output "$preparePhase-$dataset.json"))) { throw "Missing preflight/tuning: $dataset" }
            for ($fork = 1; $fork -le $Forks; $fork++) {
                $engines = if ($fork % 2 -eq 1) { @('kiara', 'sqlite') } else { @('sqlite', 'kiara') }
                foreach ($engine in $engines) {
                    Invoke-Worker -Arguments @('--phase', 'measure', '--profile', $Profile, '--engine', $engine, '--dataset', $dataset, '--fork', "$fork",
                        '--tuning', "/results/$preparePhase-$dataset.json", '--output', "/results/$engine-$dataset-$fork.json")
                }
            }
        }
    }
    if ($Stage -in @('All', 'Measure', 'Collect')) {
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
        $raw = if ($ResultsName -eq 'sqlite-execution') { 'benchmarks\sqlite-comparison.json' }
            elseif ($ResultsName -eq 'sqlite-generic-execution') { 'benchmarks\sqlite-untuned-comparison.json' } else { Join-Path $output 'comparison.json' }
        $report = if ($ResultsName -eq 'sqlite-execution') { 'docs\sqlite-comparison.md' }
            elseif ($ResultsName -eq 'sqlite-generic-execution') { 'docs\sqlite-untuned-comparison.md' } else { Join-Path $output 'comparison.md' }
        $deadlineArgs = if ($Profile -eq 'generic') { @('--query-timeout-ms', "$QueryTimeoutMs") } else { @() }
        & $java -cp benchmarks\target\kiara-lucene-benchmark-1.0-SNAPSHOT.jar `
            com.kapoorlabs.benchmark.SqliteBenchmark --collect $output --datasets ($Datasets -join ',') `
            --profile $Profile --forks $Forks --output $raw --report $report @deadlineArgs
        if ($LASTEXITCODE -ne 0) { throw 'Collection or report validation failed' }
    }
} finally {
    try {
        $active = & docker ps -q --filter "name=^/$container$"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot verify benchmark container cleanup' }
        if ($active) { Invoke-Docker -Arguments @('rm', '--force', $container) }
    } finally { Pop-Location }
}
