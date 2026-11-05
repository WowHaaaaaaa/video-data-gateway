@echo off
REM Push a real camera to ZLMediaKit.
REM Usage: push-camera.bat "Integrated Camera" [streamId]
REM        push-camera.bat "RGB Camera" usbcam
setlocal
set DEV=%~1
set STREAM=%~2
if "%DEV%"=="" (
    echo Usage: push-camera.bat ^"device name^" [streamId]
    echo Run scripts\camera-check.bat first to find a working device name.
    pause
    exit /b 1
)
if "%STREAM%"=="" set STREAM=usbcam
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-camera.ps1" -Device "%DEV%" -Stream "%STREAM%"
endlocal
