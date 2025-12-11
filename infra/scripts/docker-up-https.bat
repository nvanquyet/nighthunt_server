@echo off
REM Docker compose up with HTTPS script (Windows)

echo Starting NightHunt services with HTTPS...

REM Check if SSL certificate exists
if not exist "src\main\resources\certs\keystore.p12" (
    echo SSL certificate not found. Generating...
    call infra\scripts\generate-ssl-cert.bat
)

REM Set SSL environment variables
set SSL_ENABLED=true
if "%SSL_KEYSTORE_PASSWORD%"=="" set SSL_KEYSTORE_PASSWORD=changeit
if "%SSL_KEY_ALIAS%"=="" set SSL_KEY_ALIAS=nighthunt
set SERVER_PORT=8443
set USE_HTTPS=true
set API_BASE_URL=https://localhost:8443

REM Start services
docker-compose -f docker-compose.yml -f docker-compose.https.yml up -d

echo Waiting for services to be ready...
timeout /t 10 /nobreak >nul

echo Services are starting with HTTPS...
echo Backend API: https://localhost:8443
echo PostgreSQL: localhost:5432
echo Redis: localhost:6379
echo.
echo Note: You may need to accept the self-signed certificate in your browser/client
echo.
echo To view logs: docker-compose logs -f
echo To stop services: docker-compose down

