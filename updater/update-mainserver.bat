@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title MDT Updater - Main server (MDT DO: scripts + email-service)
echo ===== MDT Updater: main server = scripts (main) + email-service =====
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-mainserver.ps1" %*
set "EC=%ERRORLEVEL%"
echo.
echo Exit code: %EC%
if not defined MDT_UPDATER_NO_PAUSE if "%~1"=="" pause
exit /b %EC%
