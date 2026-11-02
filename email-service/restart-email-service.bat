@echo off
setlocal
cd /d "%~dp0"
echo [MDT Email Service] restarting task...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart-email-service.ps1" %*
echo.
pause
