@echo off
REM Generate self-signed SSL certificate for local development (Windows)

set CERT_DIR=src\main\resources\certs
set KEYSTORE_FILE=%CERT_DIR%\keystore.p12
set KEYSTORE_PASSWORD=changeit
set KEY_ALIAS=nighthunt

echo Generating self-signed SSL certificate for local development...

REM Create certs directory if it doesn't exist
if not exist "%CERT_DIR%" mkdir "%CERT_DIR%"

REM Generate keystore with self-signed certificate
keytool -genkeypair ^
    -alias "%KEY_ALIAS%" ^
    -keyalg RSA ^
    -keysize 2048 ^
    -storetype PKCS12 ^
    -keystore "%KEYSTORE_FILE%" ^
    -validity 365 ^
    -storepass "%KEYSTORE_PASSWORD%" ^
    -keypass "%KEYSTORE_PASSWORD%" ^
    -dname "CN=localhost, OU=Development, O=NightHunt, L=City, ST=State, C=US"

if %ERRORLEVEL% EQU 0 (
    echo SSL certificate generated successfully!
    echo Keystore location: %KEYSTORE_FILE%
    echo Password: %KEYSTORE_PASSWORD%
    echo.
    echo To use this certificate, set these environment variables:
    echo   SSL_ENABLED=true
    echo   SSL_KEYSTORE=%KEYSTORE_FILE%
    echo   SSL_KEYSTORE_PASSWORD=%KEYSTORE_PASSWORD%
    echo   SSL_KEY_ALIAS=%KEY_ALIAS%
) else (
    echo Failed to generate SSL certificate!
    exit /b %ERRORLEVEL%
)

