@echo off
REM Start MySQL 8.0.28 on port 3307 (ascii junction workaround for non-ascii path).
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-mysql.ps1"
endlocal
