@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title MDT Updater - Email service (keep config.json and logs)
echo ===== MDT Updater: email-service (add/update, keep config.json + logs) =====
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-email-service.ps1" %*
set "EC=%ERRORLEVEL%"
echo.
echo Exit code: %EC%
if not defined MDT_UPDATER_NO_PAUSE if "%~1"=="" pause
exit /b %EC%
