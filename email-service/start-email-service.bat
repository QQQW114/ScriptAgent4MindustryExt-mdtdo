@echo off
setlocal
cd /d "%~dp0"
echo [MDT Email Service] 启动中... 配置：%~dp0config.json
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server.ps1" -Config "%~dp0config.json"
echo.
echo 服务已退出（若异常请查看 logs 目录）。
pause
