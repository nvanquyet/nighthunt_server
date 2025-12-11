@echo off
REM Build script for NightHunt Backend (Windows)

echo Building NightHunt Backend...
call gradlew.bat clean build -x test

if %ERRORLEVEL% EQU 0 (
    echo Build completed successfully!
) else (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

