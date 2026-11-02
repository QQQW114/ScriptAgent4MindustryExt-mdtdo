@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title MDT Email Service
echo [MDT Email Service] 启动中...
echo 配置：%~dp0config.json

rem 如果已安装计划任务，优先通过任务启动（开机自启/崩溃自动重启都由任务负责）。
schtasks.exe /Query /TN "MDT Email Service" >nul 2>&1
if %ERRORLEVEL%==0 (
    echo 检测到计划任务 MDT Email Service，尝试启动...
    schtasks.exe /Run /TN "MDT Email Service" >nul 2>&1
    timeout /t 5 /nobreak >nul
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "try { $h = Invoke-RestMethod http://127.0.0.1:40150/health -TimeoutSec 5; Write-Host ('健康检查通过：' + ($h | ConvertTo-Json -Compress)) } catch { Write-Host '服务尚未就绪，请查看 email-service\logs 或运行 install-scheduled-task.ps1 -Status' -ForegroundColor Yellow }"
    echo.
    pause
    exit /b 0
)

echo 未安装计划任务，前台启动（关闭窗口即停止；建议管理员运行 install-scheduled-task.ps1）。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server.ps1" -Config "%~dp0config.json"
echo.
echo 服务已退出（若异常请查看 logs 目录）。
pause
