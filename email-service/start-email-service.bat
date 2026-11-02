@echo off
setlocal
cd /d "%~dp0"
title MDT Email Service
echo [MDT Email Service] starting...

schtasks.exe /Query /TN "MDT Email Service" >nul 2>&1
if errorlevel 1 goto Foreground

echo Scheduled task found; starting it...
schtasks.exe /Run /TN "MDT Email Service" >nul 2>&1
timeout /t 5 /nobreak >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { $h = Invoke-RestMethod http://127.0.0.1:40150/health -TimeoutSec 5; Write-Host ('health: ' + ($h | ConvertTo-Json -Compress)) } catch { Write-Host 'service not ready; check email-service\logs or run install-scheduled-task.ps1 -Status' }"
echo.
pause
exit /b 0

:Foreground
echo No scheduled task; starting foreground (close window stops service; install task for production).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server.ps1" -Config "%~dp0config.json"
echo.
echo Service exited. Check logs directory if error.
pause
