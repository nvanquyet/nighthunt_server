@echo off
REM Development environment startup script (Windows)

echo Starting NightHunt development environment...

REM Check if Docker is running
docker info >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Error: Docker is not running. Please start Docker and try again.
    exit /b 1
)

REM Start services
docker-compose up -d postgres redis

echo Waiting for services to be ready...
timeout /t 5 /nobreak >nul

REM Check if services are running
echo Checking service health...
docker-compose ps

echo Development environment is ready!
echo PostgreSQL: localhost:5432
echo Redis: localhost:6379
echo.
echo To start the backend application, run: gradlew.bat bootRun

