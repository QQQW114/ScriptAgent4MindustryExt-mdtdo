@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title MDT Updater - Plugins (strict mirror)
echo ===== MDT Updater: plugins (strict mirror) =====
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-plugins.ps1" %*
set "EC=%ERRORLEVEL%"
echo.
echo Exit code: %EC%
if not defined MDT_UPDATER_NO_PAUSE if "%~1"=="" pause
exit /b %EC%
