@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title MDT Updater - Web portal (strict mirror)
echo ===== MDT Updater: stats-web (strict mirror) =====
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-web.ps1" %*
set "EC=%ERRORLEVEL%"
echo.
echo Exit code: %EC%
if not defined MDT_UPDATER_NO_PAUSE if "%~1"=="" pause
exit /b %EC%
