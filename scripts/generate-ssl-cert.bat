@echo off
REM ============================================================================
REM SSL Certificate Generator for NightHunt Backend (Windows)
REM ============================================================================
REM This script automatically generates a self-signed SSL certificate
REM for development and testing purposes.
REM
REM For production, use Let's Encrypt instead.
REM ============================================================================

echo =====================================
echo NightHunt SSL Certificate Generator
echo =====================================
echo.

REM Configuration
set CERTS_DIR=src\main\resources\certs
set KEYSTORE_FILE=%CERTS_DIR%\keystore.p12
set KEYSTORE_PASSWORD=nighthunt-ssl-2026
set KEY_ALIAS=nighthunt
set VALIDITY_DAYS=365

REM Check if keytool is available
where keytool >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] keytool not found
    echo keytool is part of Java JDK. Please install JDK and add it to PATH.
    exit /b 1
)

REM Create certs directory
echo Creating certificates directory...
if not exist "%CERTS_DIR%" mkdir "%CERTS_DIR%"

REM Check if keystore already exists
if exist "%KEYSTORE_FILE%" (
    echo Keystore already exists at: %KEYSTORE_FILE%
    set /p REGENERATE="Do you want to regenerate it? (y/N): "
    if /i not "%REGENERATE%"=="y" (
        echo Using existing keystore.
        exit /b 0
    )
    echo Removing existing keystore...
    del "%KEYSTORE_FILE%"
)

REM Generate self-signed certificate
echo Generating self-signed SSL certificate...
keytool -genkeypair ^
    -alias "%KEY_ALIAS%" ^
    -keyalg RSA ^
    -keysize 2048 ^
    -storetype PKCS12 ^
    -keystore "%KEYSTORE_FILE%" ^
    -validity %VALIDITY_DAYS% ^
    -storepass "%KEYSTORE_PASSWORD%" ^
    -keypass "%KEYSTORE_PASSWORD%" ^
    -dname "CN=localhost, OU=NightHunt Development, O=NightHunt, L=Ho Chi Minh, ST=Vietnam, C=VN" ^
    -ext "SAN=dns:localhost,ip:127.0.0.1,ip:::1"

if %ERRORLEVEL% neq 0 (
    echo [ERROR] Failed to generate certificate
    exit /b 1
)

echo.
echo [SUCCESS] SSL Certificate generated successfully!
echo.
echo Certificate Details:
echo   Location: %KEYSTORE_FILE%
echo   Alias: %KEY_ALIAS%
echo   Password: %KEYSTORE_PASSWORD%
echo   Validity: %VALIDITY_DAYS% days
echo.
echo [NOTE] This is a self-signed certificate for development only.
echo [NOTE] For production, use Let's Encrypt or a commercial CA.
echo.

REM List certificate details
echo Certificate Information:
keytool -list -v -keystore "%KEYSTORE_FILE%" -storepass "%KEYSTORE_PASSWORD%" -alias "%KEY_ALIAS%"

echo.
echo [SUCCESS] Setup complete! You can now run:
echo   docker compose up -d --build
echo.

pause
