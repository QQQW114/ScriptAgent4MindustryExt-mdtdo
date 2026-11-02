@echo off
setlocal
set DIR=%~dp0
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 "%DIR%migrate-account.py" %*
) else (
  python "%DIR%migrate-account.py" %*
)
