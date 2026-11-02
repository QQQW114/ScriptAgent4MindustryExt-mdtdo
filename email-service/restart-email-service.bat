@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
echo [MDT Email Service] 重启邮箱服务任务...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart-email-service.ps1" %*
echo.
pause
