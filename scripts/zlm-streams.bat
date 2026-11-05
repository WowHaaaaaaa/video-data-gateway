@echo off
REM List live streams on ZLMediaKit in a compact table.
REM Usage: zlm-streams.bat            (default secret from config)
REM        zlm-streams.bat <secret>
setlocal
set SECRET=%~1
if "%SECRET%"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zlm-streams.ps1"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zlm-streams.ps1" -Secret "%SECRET%"
)
echo.
pause
endlocal
