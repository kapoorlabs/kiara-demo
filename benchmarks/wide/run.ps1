param(
    [ValidateSet('All', 'VoltDb', 'SqliteTuned', 'SqliteGeneric', 'Lucene', 'Collect')]
    [string]$Stage = 'All',
    [switch]$Smoke
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$prefix = if ($Smoke) { 'wide-smoke' } else { 'wide' }
$datasets = if ($Smoke) { @('wide-1000') } else { @('wide-10000', 'wide-100000') }
$forks = if ($Smoke) { 1 } else { 5 }
$base = Join-Path $root "benchmarks\target\$prefix"
$jar = Join-Path $root 'benchmarks\target\kiara-lucene-benchmark-1.0-SNAPSHOT.jar'
$container = 'kiara-wide-' + [Guid]::NewGuid().ToString('N')
$measuredHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
function Invoke-Docker {
    param([string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed ($LASTEXITCODE): $Arguments" }
}
function Assert-FrozenExecutable {
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $measuredHash) {
        throw 'Executable changed during experiment; do not combine stages'
    }
}
Push-Location $root
try {
    New-Item -ItemType Directory -Path $base -Force | Out-Null
    $archive = Join-Path $base "measured-$($measuredHash.Substring(0,8)).jar"
    if (-not (Test-Path -LiteralPath $archive)) { Copy-Item -LiteralPath $jar -Destination $archive }
    if ($Stage -in @('All', 'VoltDb')) {
        & .\benchmarks\voltdb\run-execution.ps1 -Datasets $datasets -Forks $forks `
            -ResultsName "$prefix-voltdb" -ProjectName "kiara-$prefix-voltdb"
        Assert-FrozenExecutable
    }
    if ($Stage -in @('All', 'SqliteTuned')) {
        & .\benchmarks\sqlite\run.ps1 -Profile tuned -Datasets $datasets -Forks $forks -ResultsName "$prefix-sqlite-tuned"
        Assert-FrozenExecutable
    }
    if ($Stage -in @('All', 'SqliteGeneric')) {
        & .\benchmarks\sqlite\run.ps1 -Profile generic -Datasets $datasets -Forks $forks -ResultsName "$prefix-sqlite-generic"
        Assert-FrozenExecutable
    }
    if ($Stage -in @('All', 'Lucene')) {
        $folder = Join-Path $root "benchmarks\target\$prefix-lucene"
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
        $image = "kiara-wide-benchmark:$prefix"
        Invoke-Docker -Arguments @('build', '-f', 'benchmarks\sqlite\Dockerfile', '-t', $image, 'benchmarks')
        $imageId = & docker image inspect $image --format '{{.Id}}'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Lucene image' }
        $info = & docker info --format '{{json .}}' | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker platform' }
        $platform = [ordered]@{
            recordedAt = [DateTime]::UtcNow.ToString('o')
            hostCpu = (Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)
            dockerVersion = $info.ServerVersion
            dockerKernel = $info.KernelVersion
            workerImageId = $imageId
            cpuQuota = 4
            memoryBytes = 8589934592
            network = 'none'
            benchmarkJarSha256 = $measuredHash
            datasets = $datasets
            forks = $forks
        } | ConvertTo-Json -Depth 6
        [IO.File]::WriteAllText((Join-Path $folder 'platform.json'), $platform, [Text.UTF8Encoding]::new($false))
        foreach ($dataset in $datasets) {
            for ($fork = 1; $fork -le $forks; $fork++) {
                $engines = if ($fork % 2 -eq 1) { @('kiara', 'lucene') } else { @('lucene', 'kiara') }
                foreach ($engine in $engines) {
                    Invoke-Docker -Arguments @('run', '--rm', '--name', $container, '--network', 'none', '--cpus', '4',
                        '--memory', '8g', '--memory-swap', '8g', '--mount', "type=bind,source=$folder,target=/results",
                        '--entrypoint', 'java', $image, '-Xms1g', '-Xmx4g', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=4',
                        '-Duser.timezone=UTC', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '-cp', 'benchmark.jar',
                        'com.kapoorlabs.benchmark.WideBenchmark', '--engine', $engine, '--dataset', $dataset,
                        '--fork', "$fork", '--output', "/results/$engine-$dataset-$fork.json")
                    Assert-FrozenExecutable
                }
            }
        }
    }
    if ($Stage -in @('All', 'Collect')) {
        $raw = if ($Smoke) { Join-Path $base 'comparison.json' } else { 'benchmarks\wide-filter-comparison.json' }
        $report = if ($Smoke) { Join-Path $base 'comparison.md' } else { 'docs\wide-filter-comparison.md' }
        & "$env:JAVA_HOME\bin\java.exe" -Xmx4g -cp $jar com.kapoorlabs.benchmark.WideBenchmark `
            --collect benchmarks\target --prefix $prefix --datasets ($datasets -join ',') --forks "$forks" `
            --output $raw --report $report
        if ($LASTEXITCODE -ne 0) { throw 'Wide collection failed' }
        & node .\benchmarks\wide\verify.cjs $raw $report
        if ($LASTEXITCODE -ne 0) { throw 'Independent verification failed' }
    }
} finally {
    try {
        $active = & docker ps -q --filter "name=^/$container$"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot check wide worker cleanup' }
        if ($active) { Invoke-Docker -Arguments @('rm', '--force', $container) }
    } finally { Pop-Location }
}
