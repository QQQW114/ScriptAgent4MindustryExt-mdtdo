@echo off
rem MDT DO server portal launcher. Keep this file ASCII-only: cmd reads .bat with ANSI codepage.
chcp 65001 >nul
set "script_dir=%~dp0"

rem args are forwarded, e.g.:
rem   start-web.bat -Bind 0.0.0.0 -Port 8090
rem   start-web.bat -InstallAcl
rem   start-web.bat -GenPasswordHash "your-new-password"
powershell -ExecutionPolicy Bypass -NoProfile -File "%script_dir%start-web.ps1" %*

if errorlevel 1 (
    echo.
    echo [ERROR] start-web.ps1 exited with an error, check messages above.
    pause
)
