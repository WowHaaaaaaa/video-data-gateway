@echo off
REM One-click: push the built-in laptop camera (Integrated Camera) to ZLMediaKit.
REM Usage: push-builtin-camera.bat          (default stream id = incam)
REM        push-builtin-camera.bat mytest   (custom stream id)
setlocal
set STREAM=%~1
if "%STREAM%"=="" set STREAM=incam
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-builtin-camera.ps1" -Stream "%STREAM%"
echo.
echo Push stopped. Press any key to close.
pause >nul
endlocal
