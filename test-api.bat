@echo off
REM Script để test các API endpoints (Windows)

set BASE_URL=http://localhost:8080
set TOKEN=

echo === Testing NightHunt Backend API ===
echo.

REM Test 1: Health Check
echo 1. Testing Health Check...
curl -s %BASE_URL%/actuator/health
echo.
echo.

REM Test 2: Register
echo 2. Testing Register...
set TIMESTAMP=%RANDOM%
curl -X POST %BASE_URL%/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"testuser%TIMESTAMP%\",\"email\":\"test%TIMESTAMP%@example.com\",\"password\":\"password123\",\"confirmPassword\":\"password123\"}"
echo.
echo.

echo === Testing Complete ===
echo Note: For full testing, use Postman or similar tool with proper JSON parsing

