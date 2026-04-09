# =============================================================================
# NightHunt - Production Deploy
# Build image → push registry → restart HTTPS production container on VPS
#
# Usage (local):
#   .\scripts\deploy.ps1 -Registry "ghcr.io/yourname" -Tag "v1.2.0"
#
# Usage (CI — set env vars instead of params):
#   $env:REGISTRY   = "ghcr.io/yourname"
#   $env:IMAGE_TAG  = "v1.2.0"
#   $env:VPS_HOST   = "your.vps.ip"
#   $env:VPS_USER   = "ubuntu"
#   $env:VPS_KEY    = "C:\keys\id_rsa"     # path to SSH private key
#   .\scripts\deploy.ps1
# =============================================================================

param(
    [string]$Registry  = $env:REGISTRY,
    [string]$Tag       = ($env:IMAGE_TAG ?? "latest"),
    [string]$VpsHost   = $env:VPS_HOST,
    [string]$VpsUser   = ($env:VPS_USER   ?? "ubuntu"),
    [string]$VpsKey    = $env:VPS_KEY,
    [string]$VpsDir    = ($env:VPS_DIR    ?? "/opt/nighthunt"),
    [switch]$SkipPush,
    [switch]$SkipDeploy
)

$ErrorActionPreference = "Stop"
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir

function Step([string]$n, [string]$label) {
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor DarkGray
    Write-Host "  [$n]  $label" -ForegroundColor Cyan
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "╔═══════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║   NightHunt — Production Deploy               ║" -ForegroundColor Cyan
Write-Host "╚═══════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

if (-not $Registry -and -not $SkipPush) {
    Write-Host "❌ -Registry not set. Pass -Registry 'ghcr.io/yourname' or set `$env:REGISTRY" -ForegroundColor Red
    exit 1
}

$ImageName = if ($Registry) { "$Registry/nighthunt-backend:$Tag" } else { "nighthunt-backend:$Tag" }
Write-Host "   Image : $ImageName"
Write-Host "   VPS   : $(if ($VpsHost) { $VpsHost } else { '(skip deploy, -SkipDeploy)' })"

Push-Location $ProjectRoot

try {

    # ── Step 1: Gradle build ─────────────────────────────────────────────────
    Step 1 "Gradle Build + Test"
    & .\gradlew.bat build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    Write-Host "✅ Build OK" -ForegroundColor Green

    # ── Step 2: Docker build ─────────────────────────────────────────────────
    Step 2 "Docker Build  →  $ImageName"
    docker build -t $ImageName -t "${ImageName -replace ":$Tag", ":latest"}" .
    if ($LASTEXITCODE -ne 0) { throw "Docker build failed" }
    Write-Host "✅ Image built" -ForegroundColor Green

    # ── Step 3: Docker push ───────────────────────────────────────────────────
    if (-not $SkipPush) {
        Step 3 "Docker Push"
        docker push $ImageName
        if ($LASTEXITCODE -ne 0) { throw "Docker push failed" }
        # Also push :latest tag
        $latestImage = "$Registry/nighthunt-backend:latest"
        docker tag $ImageName $latestImage
        docker push $latestImage
        Write-Host "✅ Pushed $ImageName + :latest" -ForegroundColor Green
    } else {
        Write-Host "[3] Docker push skipped (-SkipPush)" -ForegroundColor Yellow
    }

    # ── Step 4: SSH deploy ────────────────────────────────────────────────────
    if (-not $SkipDeploy) {
        Step 4 "SSH Deploy  →  $VpsUser@$VpsHost:$VpsDir"

        if (-not $VpsHost) {
            Write-Host "⚠️  -VpsHost not set — skipping SSH deploy." -ForegroundColor Yellow
        } else {
            $sshArgs = @("-o", "StrictHostKeyChecking=no")
            if ($VpsKey) { $sshArgs += @("-i", $VpsKey) }

            # Remote commands
            $remoteCmd = @"
set -e
cd $VpsDir
test -f .env.production
echo "--- Pulling new image ---"
docker compose --env-file .env.production pull backend
echo "--- Restarting backend ---"
docker compose --env-file .env.production up -d --no-deps --force-recreate backend
echo "--- Waiting for health ---"
sleep 10
curl -sk https://localhost:8443/api/actuator/health | grep -q '"status":"UP"' && echo "Backend UP" || (echo "Backend NOT healthy" && exit 1)
"@

            ssh @sshArgs "$VpsUser@$VpsHost" $remoteCmd
            if ($LASTEXITCODE -ne 0) { throw "SSH deploy failed (exit $LASTEXITCODE)" }
            Write-Host "✅ Deployed to $VpsHost" -ForegroundColor Green
        }
    } else {
        Write-Host "[4] SSH deploy skipped (-SkipDeploy)" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "╔═══════════════════════════════════════════════╗" -ForegroundColor Green
    Write-Host "║   🎉 Deploy complete!                          ║" -ForegroundColor Green
    Write-Host "╚═══════════════════════════════════════════════╝" -ForegroundColor Green
    Write-Host ""

} catch {
    Write-Host ""
    Write-Host "❌ Deploy FAILED: $_" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}
