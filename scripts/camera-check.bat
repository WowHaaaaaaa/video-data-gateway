@echo off
REM Detect which DirectShow cameras can actually be opened, then optionally push.
REM Usage: camera-check.bat            (only detect)
REM        camera-check.bat -Push      (detect and push the first working camera)
REM        camera-check.bat -Push -Stream usbcam
setlocal
set ARGS=
:loop
if "%~1"=="" goto run
set ARGS=%ARGS% %1
shift
goto loop
:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0camera-check.ps1" %ARGS%
echo.
pause
endlocal
