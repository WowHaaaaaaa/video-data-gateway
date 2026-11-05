@echo off
REM Start the video data gateway (Spring Boot).
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-gateway.ps1" %*
endlocal
