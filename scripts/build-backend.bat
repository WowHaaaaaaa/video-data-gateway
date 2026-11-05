@echo off
REM Build backend (Maven). Produces backend\target\video-data-gateway.jar
REM Usage: build-backend.bat          (package)
REM        build-backend.bat clean    (clean then package)
setlocal
set GOAL=%~1
if "%GOAL%"=="" set GOAL=package
if /I "%GOAL%"=="clean" set GOAL=clean package
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-backend.ps1" -Goal "%GOAL%"
echo.
pause
endlocal
