@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title MDT Updater - Sub server (MDC DO: scripts only, mdc-do)
echo ===== MDT Updater: sub server = scripts only (branch mdc-do) =====
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update-subserver.ps1" %*
set "EC=%ERRORLEVEL%"
echo.
echo Exit code: %EC%
if not defined MDT_UPDATER_NO_PAUSE if "%~1"=="" pause
exit /b %EC%
