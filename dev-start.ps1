
# dev-start.ps1 - NightHunt Local Dev Startup
# Vi tri: W:\Unity\Shotter\NightHuntServer\dev-start.ps1
# Chay: double-click dev-start.bat  (hoac chay bat file)

param(
    [switch]$ForceRegen,
    [switch]$NoBuild
)

Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force 2>$null

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$CERT_DIR = "src\main\resources\certs"
$KEYSTORE  = "$CERT_DIR\keystore.p12"
$PEM_CERT  = "$CERT_DIR\localhost.pem"
$PEM_KEY   = "$CERT_DIR\localhost-key.pem"
$ENV_FILE  = ".env.local"

function Step($msg)  { Write-Host "" ; Write-Host "[>>] $msg" -ForegroundColor Cyan }
function OK($msg)    { Write-Host "  [OK] $msg" -ForegroundColor Green }
function WARN($msg)  { Write-Host "  [!!] $msg" -ForegroundColor Yellow }
function FAIL($msg)  { Write-Host "" ; Write-Host "[FAIL] $msg" -ForegroundColor Red; Read-Host "Nhan Enter de thoat"; exit 1 }

Write-Host ""
Write-Host "  NightHunt Dev Stack - $(Get-Date -Format 'HH:mm:ss')" -ForegroundColor Magenta
Write-Host "  Thu muc: $scriptDir"

# ----------------------------------------------------------------
# STEP 1: Docker
# ----------------------------------------------------------------
Step "Kiem tra Docker Desktop..."

$dockerOk = $false
try {
    docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) { $dockerOk = $true }
} catch { }

if (-not $dockerOk) {
    FAIL "Docker Desktop chua chay. Hay mo Docker Desktop truoc."
}
OK "Docker dang chay"

# ----------------------------------------------------------------
# STEP 2: mkcert
# ----------------------------------------------------------------
Step "Kiem tra mkcert..."

$mkcertOk = $false
try {
    $v = & mkcert -version 2>&1
    if ($LASTEXITCODE -eq 0) { $mkcertOk = $true; OK "mkcert: $v" }
} catch { }

if (-not $mkcertOk) {
    WARN "mkcert chua co. Dang cai qua winget..."
    winget install FiloSottile.mkcert --silent --accept-package-agreements --accept-source-agreements 2>&1 | Out-Null
    $env:PATH = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + `
                [System.Environment]::GetEnvironmentVariable("Path","User")
    try {
        $v = & mkcert -version 2>&1
        if ($LASTEXITCODE -eq 0) { $mkcertOk = $true; OK "mkcert da cai: $v" }
    } catch { }
    if (-not $mkcertOk) {
        FAIL "Khong cai duoc mkcert tu dong.`n  Tai thu cong: https://github.com/FiloSottile/mkcert/releases`n  -> download mkcert-v*-windows-amd64.exe, doi ten mkcert.exe, bo vao C:\Windows\System32"
    }
}

# ----------------------------------------------------------------
# STEP 3: mkcert -install (Windows Certificate Store)
# ----------------------------------------------------------------
Step "Install mkcert CA vao Windows Certificate Store..."
Write-Host "  (Buoc nay de Unity Editor tin tuong HTTPS localhost)" -ForegroundColor Gray

& mkcert -install
if ($LASTEXITCODE -ne 0) {
    WARN "mkcert -install gap loi. Co the can quyen Admin."
    WARN "Thu: click phai dev-start.bat -> Run as Administrator"
} else {
    OK "CA da duoc trust boi Windows"
    OK "Unity Editor se tu tin tuong HTTPS localhost:8443"
}

# ----------------------------------------------------------------
# STEP 4: Tao cert localhost
# ----------------------------------------------------------------
Step "Tao SSL cert cho localhost..."

if (-not (Test-Path $CERT_DIR)) {
    New-Item -ItemType Directory -Path $CERT_DIR -Force | Out-Null
    OK "Tao thu muc: $CERT_DIR"
}

$needRegen = $ForceRegen -or (-not (Test-Path $KEYSTORE)) -or (-not (Test-Path $PEM_CERT))

if ($needRegen) {
    Write-Host "  Tao mkcert PEM cert..." -ForegroundColor Gray
    Push-Location $CERT_DIR
    & mkcert -cert-file localhost.pem -key-file localhost-key.pem localhost 127.0.0.1
    $mkcertResult = $LASTEXITCODE
    Pop-Location

    if ($mkcertResult -ne 0 -or -not (Test-Path $PEM_CERT)) {
        FAIL "mkcert khong tao duoc cert."
    }
    OK "PEM cert da tao: $PEM_CERT"

    # Convert PEM -> PKCS12 dung Docker (khong can openssl tren may)
    Write-Host "  Convert PEM -> PKCS12 (dung Docker openssl)..." -ForegroundColor Gray

    if (Test-Path $KEYSTORE) { Remove-Item $KEYSTORE -Force }

    $absCertDir = (Resolve-Path $CERT_DIR).Path
    $dockerVol  = $absCertDir.Replace('\', '/')

    docker run --rm `
        -v "${dockerVol}:/certs" `
        alpine:latest `
        sh -c "apk add --no-cache openssl > /dev/null 2>&1 ; openssl pkcs12 -export -in /certs/localhost.pem -inkey /certs/localhost-key.pem -out /certs/keystore.p12 -name nighthunt -passout pass:changeit"

    if (Test-Path $KEYSTORE) {
        OK "keystore.p12 tao thanh cong"
        OK "Cert nay duoc Windows TRUST (mkcert CA da install)"
    } else {
        WARN "Khong tao duoc keystore.p12"
        WARN "Thu cach thu cong (can openssl):"
        WARN "  openssl pkcs12 -export -in $PEM_CERT -inkey $PEM_KEY -out $KEYSTORE -name nighthunt -passout pass:changeit"
    }
} else {
    OK "Cert da ton tai (dung -ForceRegen de tao lai)"
}

# ----------------------------------------------------------------
# STEP 5: .env.local
# ----------------------------------------------------------------
Step "Kiem tra .env.local..."

if (-not (Test-Path $ENV_FILE)) {
    Write-Host "  Tao .env.local voi gia tri mac dinh..." -ForegroundColor Gray

    $envContent = @(
        "# NightHunt Local Dev - Tao boi dev-start.ps1",
        "",
        "MYSQL_DATABASE=nighthunt",
        "MYSQL_USER=nighthunt",
        "MYSQL_PASSWORD=nighthunt123",
        "MYSQL_ROOT_PASSWORD=root123",
        "DB_URL=jdbc:mysql://mysql:3306/nighthunt",
        "DB_USERNAME=nighthunt",
        "DB_PASSWORD=nighthunt123",
        "",
        "REDIS_HOST=redis",
        "REDIS_PORT=6379",
        "REDIS_PASSWORD=",
        "",
        "SSL_ENABLED=true",
        "SERVER_PORT=8443",
        "SSL_KEYSTORE=/app/certs/keystore.p12",
        "SSL_KEYSTORE_PASSWORD=changeit",
        "SSL_KEYSTORE_TYPE=PKCS12",
        "SSL_KEY_ALIAS=nighthunt",
        "USE_HTTPS=true",
        "API_BASE_URL=https://localhost:8443",
        "",
        "JWT_SECRET=nighthunt-local-dev-secret-min-32chars!!",
        "CSRF_ENABLED=false",
        "",
        "CORS_ALLOWED_ORIGINS=https://localhost:8443",
        "",
        "VPS_PUBLIC_IP=127.0.0.1",
        "DS_IMAGE_REF=ghcr.io/yourname/nighthunt-ds:latest",
        "DS_ADMIN_SECRET=local-admin-secret",
        "DS_BACKEND_INTERNAL_URL=https://nighthunt-backend:8443",
        "DS_PORT_START=7777",
        "DS_PORT_END=7900",
        "DS_MAX_PLAYERS=16",
        "DS_DOCKER_ENABLED=false",
        "",
        "DASHBOARD_PORT=3001",
        "DASHBOARD_BACKEND_URL=https://nighthunt-backend:8443",
        "DASHBOARD_ADMIN_USER=admin",
        "DASHBOARD_ADMIN_PASS=admin123",
        "DASHBOARD_ROOT_USER=root",
        "DASHBOARD_ROOT_PASS=root123"
    )
    $envContent | Out-File -FilePath $ENV_FILE -Encoding ASCII
    OK ".env.local da tao"
} else {
    OK ".env.local da ton tai"
}

# ----------------------------------------------------------------
# STEP 6: Docker Compose up
# ----------------------------------------------------------------
Step "Khoi dong Docker services..."

$env:ENV_FILE = $ENV_FILE

Write-Host "  Dung services cu (force remove)..." -ForegroundColor Gray
docker compose --env-file $ENV_FILE down --remove-orphans --volumes=false 2>&1 | Out-Null
# Xoa container conflict neu con
docker rm -f nighthunt-mysql nighthunt-redis nighthunt-backend nighthunt-phpmyadmin nighthunt-dashboard 2>&1 | Out-Null

$upArgs = @("--env-file", $ENV_FILE, "up", "-d", "--force-recreate")
if (-not $NoBuild) { $upArgs += "--build" }

Write-Host "  Dang build va start (lan dau mat 2-3 phut)..." -ForegroundColor Gray
docker compose @upArgs

if ($LASTEXITCODE -ne 0) {
    FAIL "docker compose up that bai.`n  Xem logs: docker compose --env-file .env.local logs -f"
}
OK "Docker services da start"

# ----------------------------------------------------------------
# STEP 7: Health check (HTTPS)
# ----------------------------------------------------------------
Step "Cho backend ready..."

$healthUrl = "https://localhost:8443/api/actuator/health"
$maxWait   = 90
$waited    = 0
$ready     = $false

Write-Host "  Ping: $healthUrl" -ForegroundColor Gray

while ($waited -lt $maxWait) {
    try {
        $resp = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing `
                    -SkipCertificateCheck -TimeoutSec 3 -ErrorAction Stop
        if ($resp.StatusCode -eq 200) {
            $ready = $true
            OK "Backend READY sau ${waited}s"
            break
        }
    } catch { }

    Write-Host "  ${waited}s..." -NoNewline -ForegroundColor Gray
    Start-Sleep -Seconds 3
    $waited += 3
}

Write-Host ""

if (-not $ready) {
    WARN "Backend chua ready sau ${maxWait}s."
    WARN "Xem logs: docker compose --env-file .env.local logs -f backend"
}

# ----------------------------------------------------------------
# Done
# ----------------------------------------------------------------
Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
if ($ready) {
    Write-Host "  [OK] NightHunt Dev Stack san sang!" -ForegroundColor Green
} else {
    Write-Host "  [!!] Stack dang khoi dong - kiem tra logs" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "  Backend:      https://localhost:8443/api"
Write-Host "  Health:       https://localhost:8443/api/actuator/health"
Write-Host "  PhpMyAdmin:   http://localhost:8081"
Write-Host ""
Write-Host "  Unity Editor: Nhan Play - khong can config gi them" -ForegroundColor Yellow
Write-Host ""
Write-Host "  Stop:    docker compose --env-file .env.local down" -ForegroundColor Gray
Write-Host "  Logs:    docker compose --env-file .env.local logs -f backend" -ForegroundColor Gray
Write-Host "  Rebuild: .\dev-start.ps1 -ForceRegen" -ForegroundColor Gray
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""
