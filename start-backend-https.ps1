# =============================================================================
# Start NightHunt Backend in Docker with HTTPS (port 8443, production profile)
# Production-safe startup: no dev WebSocket HTTP fallback, no local-dev profile
#
# Usage: .\start-backend-https.ps1
# Stop:  .\start-backend-https.ps1 -Stop
# Logs:  docker logs nighthunt-backend -f
# =============================================================================
param([switch]$Stop, [switch]$Rebuild)

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$dashboardRoot = Join-Path $ProjectRoot "dashboard"
$appNetwork = 'nighthuntserver_nighthunt-network'
$gameNetwork = 'nighthunt_game-network'
$containers = @('nighthunt-backend', 'nighthunt-mysql', 'nighthunt-redis', 'nighthunt-dashboard', 'nighthunt-phpmyadmin-local')

if ($Stop) {
    foreach ($container in $containers) {
        docker stop $container 2>$null *> $null
        docker rm $container 2>$null *> $null
    }
    Write-Host "Production containers stopped." -ForegroundColor Yellow
    exit 0
}

if ($Rebuild) {
    Write-Host "[rebuild] Building backend image..." -ForegroundColor Yellow
    Push-Location $ProjectRoot
    docker build -t nighthuntserver-backend:latest .
    Pop-Location

    Write-Host "[rebuild] Building dashboard image..." -ForegroundColor Yellow
    Push-Location $dashboardRoot
    docker build -t nighthuntserver-dashboard:latest .
    Pop-Location
}

$envFile = Join-Path $ProjectRoot ".env.production"
if (-not (Test-Path $envFile)) { Write-Error ".env.production not found!"; exit 1 }

Push-Location $ProjectRoot

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^[A-Za-z_][A-Za-z0-9_]*=') {
        $parts = $_.Split('=', 2)
        Set-Item -Path ("Env:" + $parts[0]) -Value $parts[1]
    }
}

$requiredVars = @(
    'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD',
    'DB_URL', 'DB_USERNAME', 'DB_PASSWORD',
    'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD',
    'JWT_SECRET', 'API_BASE_URL', 'CORS_ALLOWED_ORIGINS',
    'SSL_KEYSTORE_PASSWORD', 'SSL_KEYSTORE_TYPE', 'SSL_KEY_ALIAS',
    'DS_BACKEND_INTERNAL_URL', 'DS_ADMIN_SECRET', 'DS_IMAGE_REF',
    'DASHBOARD_BACKEND_URL', 'DASHBOARD_PORT',
    'DASHBOARD_ADMIN_USER', 'DASHBOARD_ADMIN_PASS',
    'DASHBOARD_ROOT_USER', 'DASHBOARD_ROOT_PASS'
)

$missing = $requiredVars | Where-Object { [string]::IsNullOrWhiteSpace((Get-Item -Path ("Env:" + $_) -ErrorAction SilentlyContinue).Value) }
if ($missing.Count -gt 0) {
    Write-Error (".env.production is missing required production values: " + ($missing -join ', '))
    exit 1
}

foreach ($networkName in @($appNetwork, $gameNetwork)) {
    docker network inspect $networkName *> $null
    if ($LASTEXITCODE -ne 0) {
        docker network create $networkName *> $null
    }
}

Write-Host "[1/2] Starting MySQL + Redis (production env)..." -ForegroundColor Yellow
docker stop nighthunt-mysql 2>$null; docker rm nighthunt-mysql 2>$null
docker stop nighthunt-redis 2>$null; docker rm nighthunt-redis 2>$null
docker run -d `
    --name nighthunt-mysql `
    --network $appNetwork `
    --network-alias mysql `
    -v nighthunt_mysql_data:/var/lib/mysql `
    -e "MYSQL_DATABASE=$env:MYSQL_DATABASE" `
    -e "MYSQL_USER=$env:MYSQL_USER" `
    -e "MYSQL_PASSWORD=$env:MYSQL_PASSWORD" `
    -e "MYSQL_ROOT_PASSWORD=$env:MYSQL_ROOT_PASSWORD" `
    mysql:8.0 `
    --default-authentication-plugin=mysql_native_password *> $null

docker run -d `
    --name nighthunt-redis `
    --network $appNetwork `
    --network-alias redis `
    -v nighthunt_redis_data:/data `
    redis:7-alpine `
    sh -c "redis-server --requirepass '$env:REDIS_PASSWORD'" *> $null

Start-Sleep 25

Write-Host "[2/2] Starting Backend (HTTPS:8443)..." -ForegroundColor Yellow
# Dùng docker run trực tiếp — docker compose config đang lỗi "invalid proto:" trên Windows Docker Desktop.
# Script này đọc .env.production và chạy backend với profile prod.
docker stop nighthunt-backend 2>$null; docker rm nighthunt-backend 2>$null
docker run -d `
    --name nighthunt-backend `
    --network $appNetwork `
    -p 8443:8443 `
    -v "${ProjectRoot}/src/main/resources/certs:/app/certs:ro" `
    -v /var/run/docker.sock:/var/run/docker.sock `
    -e "DB_URL=$env:DB_URL" `
    -e "DB_USERNAME=$env:DB_USERNAME" `
    -e "DB_PASSWORD=$env:DB_PASSWORD" `
    -e "REDIS_HOST=$env:REDIS_HOST" `
    -e "REDIS_PORT=$env:REDIS_PORT" `
    -e "REDIS_PASSWORD=$env:REDIS_PASSWORD" `
    -e "JWT_SECRET=$env:JWT_SECRET" `
    -e "CSRF_ENABLED=$env:CSRF_ENABLED" `
    -e "CORS_ALLOWED_ORIGINS=$env:CORS_ALLOWED_ORIGINS" `
    -e "API_BASE_URL=$env:API_BASE_URL" `
    -e "USE_HTTPS=$env:USE_HTTPS" `
    -e "VPS_PUBLIC_IP=$env:VPS_PUBLIC_IP" `
    -e "DS_IMAGE_REF=$env:DS_IMAGE_REF" `
    -e "DS_ADMIN_SECRET=$env:DS_ADMIN_SECRET" `
    -e "DS_BACKEND_INTERNAL_URL=$env:DS_BACKEND_INTERNAL_URL" `
    -e "DS_PORT_START=$env:DS_PORT_START" `
    -e "DS_PORT_END=$env:DS_PORT_END" `
    -e "DS_MAX_PLAYERS=$env:DS_MAX_PLAYERS" `
    -e "DS_MAX_MEMORY_MB=$env:DS_MAX_MEMORY_MB" `
    -e "SSL_KEYSTORE_PASSWORD=$env:SSL_KEYSTORE_PASSWORD" `
    -e "SSL_KEYSTORE_TYPE=$env:SSL_KEYSTORE_TYPE" `
    -e "SSL_KEY_ALIAS=$env:SSL_KEY_ALIAS" `
    nighthuntserver-backend:latest `
    --spring.profiles.active=prod `
    --server.port=8443 `
    --server.ssl.enabled=true `
    --server.ssl.key-store=/app/certs/keystore.p12 `
    "--server.ssl.key-store-password=$env:SSL_KEYSTORE_PASSWORD" `
    "--server.ssl.key-store-type=$env:SSL_KEYSTORE_TYPE" `
    "--server.ssl.key-alias=$env:SSL_KEY_ALIAS"

docker network connect $gameNetwork nighthunt-backend 2>$null *> $null

Write-Host "[3/3] Starting Dashboard (port $env:DASHBOARD_PORT)..." -ForegroundColor Yellow
docker stop nighthunt-dashboard 2>$null; docker rm nighthunt-dashboard 2>$null
docker run -d `
    --name nighthunt-dashboard `
    --network $appNetwork `
    -p "${env:DASHBOARD_PORT}:${env:DASHBOARD_PORT}" `
    -e "BACKEND_URL=$env:DASHBOARD_BACKEND_URL" `
    -e "PORT=$env:DASHBOARD_PORT" `
    -e "DASHBOARD_ADMIN_USER=$env:DASHBOARD_ADMIN_USER" `
    -e "DASHBOARD_ADMIN_PASS=$env:DASHBOARD_ADMIN_PASS" `
    -e "DASHBOARD_ROOT_USER=$env:DASHBOARD_ROOT_USER" `
    -e "DASHBOARD_ROOT_PASS=$env:DASHBOARD_ROOT_PASS" `
    -e "JWT_SECRET=$env:JWT_SECRET" `
    -e "DS_ADMIN_SECRET=$env:DS_ADMIN_SECRET" `
    nighthuntserver-dashboard:latest *> $null

Pop-Location

if ($LASTEXITCODE -ne 0) { Write-Error "Failed to start container!"; exit 1 }

Write-Host ""
Write-Host "Waiting for startup..." -ForegroundColor DarkGray
$ok = $false
$dashboardOk = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep 2
    $status = docker inspect --format "{{.State.Status}}" nighthunt-backend 2>$null
    if ($status -eq "running") {
        try {
            Add-Type -TypeDefinition @"
using System.Net;
public class _TrustAllX { public static void Set() {
    ServicePointManager.ServerCertificateValidationCallback = (s,c,ch,e) => true; }}
"@ -ErrorAction SilentlyContinue
            [_TrustAllX]::Set()
            $health = (New-Object System.Net.WebClient).DownloadString("https://localhost:8443/api/actuator/health")
            if ($health -like '*UP*') {
                $ok = $true
                try {
                    $dashboardHealth = (New-Object System.Net.WebClient).DownloadString("http://localhost:$env:DASHBOARD_PORT/health")
                    if ($dashboardHealth -like '*ok*') {
                        $dashboardOk = $true
                    }
                } catch { }
                break
            }
        } catch { }
    }
}

if ($ok) {
    Write-Host ""
    Write-Host "Backend is UP on https://localhost:8443" -ForegroundColor Green
    Write-Host "  API:       https://localhost:8443/api/" -ForegroundColor Cyan
    Write-Host "  WebSocket: wss://localhost:8443/api/ws/game" -ForegroundColor Cyan
    Write-Host "  Health:    https://localhost:8443/api/actuator/health" -ForegroundColor Cyan
    if ($dashboardOk) {
        Write-Host "  Dashboard: http://localhost:$env:DASHBOARD_PORT" -ForegroundColor Cyan
    } else {
        Write-Host "  Dashboard: starting or unavailable on http://localhost:$env:DASHBOARD_PORT" -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "Stop: .\start-backend-https.ps1 -Stop" -ForegroundColor DarkGray
    Write-Host "Logs: docker logs nighthunt-backend -f" -ForegroundColor DarkGray
} else {
    Write-Host "Backend may still be starting. Check: docker logs nighthunt-backend" -ForegroundColor Yellow
}
