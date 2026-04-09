# =============================================================================
# NightHunt - Production Health Check
# Verifies build prerequisites, running production containers, and HTTPS API.
# =============================================================================

param(
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$ApiOnly,
    [switch]$Wait,
    [int]$WaitTimeout = 90,
    [string]$ApiBase = "https://localhost:8443"
)

$ErrorActionPreference = "SilentlyContinue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$pass = 0
$fail = 0

Add-Type @"
using System.Net;
public static class TrustLocalhostCertificate {
    public static void Enable() {
        ServicePointManager.ServerCertificateValidationCallback = (sender, certificate, chain, errors) => true;
    }
}
"@ -ErrorAction SilentlyContinue
[TrustLocalhostCertificate]::Enable()

function Step([string]$label) {
    Write-Host ""
    Write-Host "──────────────────────────────────────────────" -ForegroundColor DarkGray
    Write-Host "  $label" -ForegroundColor Cyan
    Write-Host "──────────────────────────────────────────────" -ForegroundColor DarkGray
}

function OK([string]$msg)   { Write-Host "  OK  $msg" -ForegroundColor Green; $script:pass++ }
function FAIL([string]$msg) { Write-Host "  FAIL $msg" -ForegroundColor Red; $script:fail++ }
function WARN([string]$msg) { Write-Host "  WARN $msg" -ForegroundColor Yellow }
function INFO([string]$msg) { Write-Host "  INFO $msg" -ForegroundColor Gray }

Write-Host ""
Write-Host "NightHunt production health check" -ForegroundColor Cyan

if (-not $ApiOnly) {
    Step "1 / 4 Prerequisites"

    $java = java -version 2>&1
    if ($LASTEXITCODE -eq 0) { OK "Java available" } else { FAIL "Java 17+ is required" }

    $docker = docker --version 2>&1
    if ($LASTEXITCODE -eq 0) { OK "Docker available" } else { FAIL "Docker Desktop is required" }

    docker info *> $null
    if ($LASTEXITCODE -eq 0) { OK "Docker daemon running" } else { FAIL "Docker daemon is not running" }

    if (Test-Path "$ProjectRoot\.env.production") { OK ".env.production present" } else { FAIL ".env.production missing" }

    if (-not $SkipBuild) {
        Step "2 / 4 Gradle build"
        Push-Location $ProjectRoot
        $buildOut = & .\gradlew.bat build -x test --no-daemon 2>&1
        $buildExit = $LASTEXITCODE
        Pop-Location

        if ($buildExit -eq 0) { OK "Gradle build succeeded" }
        else {
            FAIL "Gradle build failed"
            $buildOut | Select-String "error:|FAILURE:" | Select-Object -First 10 | ForEach-Object { INFO $_ }
        }
    }

    if (-not $SkipTests) {
        Step "3 / 4 Tests"
        Push-Location $ProjectRoot
        $testOut = & .\gradlew.bat test --no-daemon 2>&1
        $testExit = $LASTEXITCODE
        Pop-Location

        if ($testExit -eq 0) { OK "Tests passed" }
        else {
            FAIL "Tests failed"
            $testOut | Select-String "FAILED|Exception" | Select-Object -First 10 | ForEach-Object { INFO $_ }
        }
    }

    Step "4 / 4 Container state"
    foreach ($container in @('nighthunt-mysql', 'nighthunt-redis', 'nighthunt-backend')) {
        $state = docker inspect --format "{{.State.Status}}" $container 2>$null
        if ($state -eq 'running') { OK "$container running" }
        elseif ($state) { WARN "$container state = $state" }
        else { WARN "$container not found" }
    }
}

Step "API smoke test ($ApiBase)"

if ($Wait) {
    $waited = 0
    $up = $false
    while ($waited -lt $WaitTimeout) {
        Start-Sleep -Seconds 3
        $waited += 3
        try {
            $health = Invoke-WebRequest -Uri "$ApiBase/api/actuator/health" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            if ($health.StatusCode -eq 200) {
                $up = $true
                break
            }
        } catch { }
    }

    if ($up) { OK "Backend responded within ${WaitTimeout}s" }
    else { FAIL "Backend did not become healthy within ${WaitTimeout}s" }
}

$checks = @(
    @{ Url = '/api/actuator/health'; Method = 'GET'; Min = 200; Max = 200; Label = 'Actuator health' },
    @{ Url = '/api/auth/login'; Method = 'POST'; Body = '{}'; Min = 400; Max = 499; Label = 'Auth route reachable' },
    @{ Url = '/api/ws/game'; Method = 'GET'; Min = 400; Max = 499; Label = 'WebSocket route reachable' }
)

foreach ($check in $checks) {
    try {
        $requestParams = @{
            Uri = "$ApiBase$($check.Url)"
            Method = $check.Method
            UseBasicParsing = $true
            TimeoutSec = 5
            ErrorAction = 'SilentlyContinue'
        }

        if ($check.ContainsKey('Body')) {
            $requestParams['Body'] = $check.Body
            $requestParams['ContentType'] = 'application/json'
        }

        $response = Invoke-WebRequest @requestParams
        $statusCode = $response.StatusCode
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if (-not $statusCode) {
            $statusCode = 0
        }
    }

    if ($statusCode -ge $check.Min -and $statusCode -le $check.Max) {
        OK "$($check.Label) -> HTTP $statusCode"
    } else {
        FAIL "$($check.Label) -> HTTP $statusCode"
    }
}

Write-Host ""
Write-Host "Passed: $pass  Failed: $fail" -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
exit $fail
