[CmdletBinding()]
param(
    [string]$ResultsDir = "",
    [string]$Pattern = "scenario-*.jtl"
)

$ErrorActionPreference = "Stop"
if (-not $ResultsDir) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $ResultsDir = Join-Path $ScriptDir "results"
}

function Percentile([double[]]$Values, [double]$P) {
    if (-not $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = $Values | Sort-Object
    $index = [Math]::Ceiling(($P / 100.0) * $sorted.Count) - 1
    if ($index -lt 0) { $index = 0 }
    if ($index -ge $sorted.Count) { $index = $sorted.Count - 1 }
    return [double]$sorted[$index]
}

$files = Get-ChildItem -LiteralPath $ResultsDir -Filter $Pattern -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "*-raw.jtl" } |
    Sort-Object Name

if (-not $files) {
    throw "No JMeter JTL files found in $ResultsDir matching $Pattern"
}

$rows = foreach ($file in $files) {
    $records = Import-Csv -LiteralPath $file.FullName
    $elapsed = @($records | ForEach-Object { [double]$_.elapsed })
    $errors = @($records | Where-Object { $_.success -ne "true" }).Count
    $timestamps = @($records | ForEach-Object { [double]$_.timeStamp })
    $durationSeconds = if ($timestamps.Count -gt 1) {
        (($timestamps | Measure-Object -Maximum).Maximum - ($timestamps | Measure-Object -Minimum).Minimum) / 1000.0
    } else {
        0
    }
    $count = $records.Count
    [pscustomobject]@{
        Scenario = [IO.Path]::GetFileNameWithoutExtension($file.Name)
        Requests = $count
        Errors = $errors
        ErrorPercent = if ($count) { [Math]::Round(($errors / $count) * 100, 2) } else { 0 }
        AvgMs = if ($count) { [Math]::Round(($elapsed | Measure-Object -Average).Average, 1) } else { 0 }
        P95Ms = [Math]::Round((Percentile $elapsed 95), 1)
        P99Ms = [Math]::Round((Percentile $elapsed 99), 1)
        ThroughputReqSec = if ($durationSeconds -gt 0) { [Math]::Round($count / $durationSeconds, 2) } else { 0 }
    }
}

$rows | Format-Table -AutoSize
