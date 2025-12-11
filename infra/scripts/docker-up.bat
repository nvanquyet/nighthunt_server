@echo off
REM Docker compose up script (Windows)

echo Starting all services with Docker Compose...
docker-compose up -d

echo Waiting for services to be ready...
timeout /t 10 /nobreak >nul

echo Services are starting...
echo Backend API: http://localhost:8080
echo PostgreSQL: localhost:5432
echo Redis: localhost:6379
echo.
echo To view logs: docker-compose logs -f
echo To stop services: docker-compose down

