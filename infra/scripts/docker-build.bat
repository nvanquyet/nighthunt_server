@echo off
REM Docker build script (Windows)

echo Building Docker image for NightHunt Backend...
docker-compose build backend

if %ERRORLEVEL% EQU 0 (
    echo Docker build completed successfully!
) else (
    echo Docker build failed!
    exit /b %ERRORLEVEL%
)

