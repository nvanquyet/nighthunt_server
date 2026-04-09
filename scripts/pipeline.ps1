# =============================================================================
# NightHunt — Full Release Pipeline (local → docker → VPS)
#
# Thứ tự:
#   Stage 1  Build + Test (Gradle)
#   Stage 2  Production-localhost smoke test (start-backend-https → health check → stop)
#   Stage 3  (optional) Deploy to VPS
#
# Usage:
#   .\scripts\pipeline.ps1                         # Stage 1 + 2 only
#   .\scripts\pipeline.ps1 -Deploy                 # includes Stage 3
#   .\scripts\pipeline.ps1 -Tag "v1.2.0" -Deploy   # tag image trước khi push
#   .\scripts\pipeline.ps1 -Stage1Only             # chỉ build + test
# =============================================================================

param(
    [switch]$Deploy,
    [switch]$Stage1Only,
    [string]$Tag       = "latest",
    [string]$Registry  = $env:REGISTRY,
    [string]$VpsHost   = $env:VPS_HOST,
    [string]$VpsUser   = ($env:VPS_USER  ?? "ubuntu"),
    [string]$VpsKey    = $env:VPS_KEY,
    [string]$VpsDir    = ($env:VPS_DIR   ?? "/opt/nighthunt"),
    [string]$ApiBase   = "https://localhost:8443"
)

$ErrorActionPreference = "SilentlyContinue"
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir

$startTime = Get-Date
$allOk     = $true

function Banner([string]$stage, [string]$title) {
    Write-Host ""
    Write-Host "╔══════════════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host "║  Stage $stage — $title" -ForegroundColor Cyan
    Write-Host "╚══════════════════════════════════════════════╝" -ForegroundColor Cyan
}

function Step([string]$msg) { Write-Host "  ► $msg" -ForegroundColor Yellow }
function OK([string]$msg)   { Write-Host "  ✅ $msg" -ForegroundColor Green }
function FAIL([string]$msg) {
    Write-Host "  ❌ $msg" -ForegroundColor Red
    $script:allOk = $false
}

Write-Host ""
Write-Host "╔══════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║   NightHunt — Release Pipeline               ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host "   Tag: $Tag" -ForegroundColor Gray
if ($Deploy) { Write-Host "   VPS: $VpsHost" -ForegroundColor Gray }

Push-Location $ProjectRoot

# ══════════════════════════════════════════════════════════════════════════════
# STAGE 1 — Build + Test
# ══════════════════════════════════════════════════════════════════════════════
Banner 1 "Build + Test"

Step "Gradle build (skip tests)..."
$buildOut = & .\gradlew.bat build -x test --no-daemon 2>&1
if ($LASTEXITCODE -eq 0) { OK "Build succeeded" }
else {
    FAIL "Build FAILED"
    $buildOut | Select-String "error:" | Select-Object -First 10 | ForEach-Object {
        Write-Host "     $_" -ForegroundColor Red
    }
}

if ($allOk) {
    Step "Running tests..."
    $testOut = & .\gradlew.bat test --no-daemon 2>&1
    if ($LASTEXITCODE -eq 0) { OK "All tests passed" }
    else {
        FAIL "Tests FAILED"
        $testOut | Select-String "FAILED|Exception" | Select-Object -First 5 |
            ForEach-Object { Write-Host "     $_" -ForegroundColor Red }
        Write-Host "     Report: build\reports\tests\test\index.html" -ForegroundColor Gray
    }
}

if (-not $allOk -or $Stage1Only) {
    Pop-Location
    $dur = [int](((Get-Date) - $startTime).TotalSeconds)
    if ($allOk) { Write-Host "`n✅ Stage 1 done  ($dur s)" -ForegroundColor Green }
    else         { Write-Host "`n❌ Pipeline stopped at Stage 1  ($dur s)" -ForegroundColor Red }
    exit ($allOk ? 0 : 1)
}

# ══════════════════════════════════════════════════════════════════════════════
# STAGE 2 — Docker full stack smoke test
# ══════════════════════════════════════════════════════════════════════════════
Banner 2 "Production-localhost Smoke Test"

if ($allOk) {
    Step "Starting HTTPS production stack..."
    & "$ProjectRoot\start-backend-https.ps1" -Rebuild
    if ($LASTEXITCODE -ne 0) {
        FAIL "start-backend-https.ps1 failed"
    } else {
        OK "Production stack started"

        Step "Running HTTPS health check..."
        & "$ScriptDir\check.ps1" -ApiOnly -Wait -WaitTimeout 90 -ApiBase $ApiBase
        if ($LASTEXITCODE -eq 0) { OK "HTTPS smoke test passed" }
        else { FAIL "HTTPS smoke test failed" }

        Step "Stopping production stack..."
        & "$ScriptDir\docker-down.ps1"
        if ($LASTEXITCODE -eq 0) { OK "Production stack stopped" }
        else { Write-Host "  WARN production stack stop had issues" -ForegroundColor Yellow }
    }
}

# ══════════════════════════════════════════════════════════════════════════════
# STAGE 3 — Deploy to VPS
# ══════════════════════════════════════════════════════════════════════════════
if ($Deploy -and $allOk) {
    Banner 3 "Deploy to VPS"

    if (-not $VpsHost) {
        Write-Host "  ⚠️  -VpsHost not set (or `$env:VPS_HOST). Skipping VPS deploy." -ForegroundColor Yellow
    } elseif (-not $Registry) {
        Write-Host "  ⚠️  -Registry not set. Skipping image push." -ForegroundColor Yellow
    } else {
        $imageName = "$Registry/nighthunt-backend:$Tag"
        Step "Tagging image as $imageName ..."
        docker tag nighthunt-backend:latest $imageName 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { FAIL "docker tag failed" }
        else {
            Step "Pushing image..."
            docker push $imageName 2>&1
            if ($LASTEXITCODE -ne 0) { FAIL "docker push failed" }
            else {
                OK "Image pushed: $imageName"

                Step "SSH deploy to $VpsUser@$VpsHost ..."
                & "$ScriptDir\deploy.ps1" `
                    -Registry $Registry -Tag $Tag `
                    -VpsHost $VpsHost -VpsUser $VpsUser `
                    -VpsKey $VpsKey -VpsDir $VpsDir `
                    -SkipPush # already pushed above

                if ($LASTEXITCODE -eq 0) { OK "Deploy to $VpsHost succeeded" }
                else                     { FAIL "Deploy to $VpsHost failed" }
            }
        }
    }
} elseif ($Deploy -and -not $allOk) {
    Write-Host ""
    Write-Host "  ⚠️  Deploy skipped — previous stages had failures." -ForegroundColor Yellow
}

# ══════════════════════════════════════════════════════════════════════════════
# Summary
# ══════════════════════════════════════════════════════════════════════════════
Pop-Location
$dur = [int](((Get-Date) - $startTime).TotalSeconds)
Write-Host ""
Write-Host "════════════════════════════════════════════════" -ForegroundColor DarkGray
if ($allOk) {
    Write-Host "  ✅ PIPELINE PASSED  (${dur}s)" -ForegroundColor Green
} else {
    Write-Host "  ❌ PIPELINE FAILED  (${dur}s)" -ForegroundColor Red
}
Write-Host "════════════════════════════════════════════════" -ForegroundColor DarkGray
Write-Host ""

exit ($allOk ? 0 : 1)
