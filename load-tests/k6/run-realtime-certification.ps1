param(
    [string]$HostName = "localhost:8443",
    [string]$HttpScheme = "https",
    [string]$AuthTokens = $env:AUTH_TOKENS,
    [string]$SessionIds = $env:SESSION_IDS,
    [string[]]$Scenarios = @("connection_ramp", "ping_storm", "soak"),
    [switch]$InsecureTls
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Script = Join-Path $ScriptDir "ws_load_test.js"
$ReportDir = Join-Path (Split-Path -Parent $ScriptDir) "reports"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

if (-not $AuthTokens) { throw "AUTH_TOKENS is required. Provide comma-separated access tokens." }
if (-not $SessionIds) { throw "SESSION_IDS is required. Provide comma-separated session ids." }

$tokenCount = @($AuthTokens.Split(",") | Where-Object { $_.Trim() }).Count
$sessionCount = @($SessionIds.Split(",") | Where-Object { $_.Trim() }).Count
$requiredCredentials = @{
    smoke = 1
    ping_storm = 1000
    soak = 1000
    connection_ramp = 10000
}

foreach ($scenario in $Scenarios) {
    if (-not $requiredCredentials.ContainsKey($scenario)) {
        throw "Unknown scenario: $scenario"
    }
    $required = $requiredCredentials[$scenario]
    if ($tokenCount -lt $required -or $sessionCount -lt $required) {
        throw "Scenario $scenario requires $required unique identities; received $tokenCount tokens and $sessionCount session ids."
    }
}

Write-Host "Unique realtime identities: tokens=$tokenCount sessions=$sessionCount"

$common = @(
    "run", $Script,
    "-e", "HOST=$HostName",
    "-e", "HTTP_SCHEME=$HttpScheme",
    "-e", "AUTH_TOKENS=$AuthTokens",
    "-e", "SESSION_IDS=$SessionIds",
    "-e", "INSECURE_TLS=$($InsecureTls.IsPresent.ToString().ToLowerInvariant())"
)

foreach ($scenario in $Scenarios) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $summary = Join-Path $ReportDir "k6-realtime-$scenario-$stamp.json"
    Write-Host "=== k6 realtime scenario: $scenario ==="
    & k6 @common -e "SCENARIO=$scenario" --summary-export $summary
    if ($LASTEXITCODE -ne 0) {
        throw "k6 scenario failed: $scenario"
    }
}
