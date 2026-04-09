@echo off
:: ============================================================
:: dev-start.bat — NightHunt Dev Stack (Double-click de chay)
:: Dat o: W:\Unity\Shotter\NightHuntServer\dev-start.bat
:: ============================================================
title NightHunt Dev Stack

:: Di vao thu muc chua bat file nay
cd /d "%~dp0"

echo.
echo  NightHunt Dev Stack - Khoi dong...
echo  Thu muc: %~dp0
echo.

:: Chay PowerShell script voi bypass execution policy
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-start.ps1" %*

:: Neu gap loi, giu cua so lai de doc loi
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Script gap loi. Xem thong tin phia tren.
    pause
)
