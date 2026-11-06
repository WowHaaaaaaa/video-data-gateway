@echo off
REM One-click: push the external USB camera (RGB Camera) to ZLMediaKit as live/us/cam
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-camera.ps1" -Device "RGB Camera" -Stream usbcam
echo.
echo Push stopped. Press any key to close.
pause >nul
endlocal
