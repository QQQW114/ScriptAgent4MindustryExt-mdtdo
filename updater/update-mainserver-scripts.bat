@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title MDT Updater - Main server scripts only (no email-service)
echo ===== MDT Updater: main server scripts only (main branch, no email-service) =====
echo.
call "%~dp0update-mainserver.bat" -NoEmailService %*
set "EC=%ERRORLEVEL%"
echo.
echo Exit code: %EC%
if not defined MDT_UPDATER_NO_PAUSE if "%~1"=="" pause
exit /b %EC%
