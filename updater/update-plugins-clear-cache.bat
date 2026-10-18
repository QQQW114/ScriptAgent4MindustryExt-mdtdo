@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title MDT Updater - Plugins + clear compile cache
echo ===== MDT Updater: plugins + clear compile cache =====
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-plugins-clear-cache.ps1" %*
set "EC=%ERRORLEVEL%"
echo.
echo Exit code: %EC%
if not defined MDT_UPDATER_NO_PAUSE if "%~1"=="" pause
exit /b %EC%
