@echo off
REM Push the Windows desktop (screen capture via gdigrab) to ZLMediaKit.
REM Usage: push-screen.bat [streamId] [fps] [maxWidth] [durationSec]
REM Examples:
REM   push-screen.bat
REM   push-screen.bat screencast
REM   push-screen.bat screencast 10
REM   push-screen.bat screencast 10 1280
REM   push-screen.bat screencast 15 0 30
setlocal
set STREAM=%~1
if "%STREAM%"=="" set STREAM=screencast
set FPS=%~2
if "%FPS%"=="" set FPS=15
set MAXW=%~3
if "%MAXW%"=="" set MAXW=0
set DUR=%~4
if "%DUR%"=="" set DUR=0
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-screen.ps1" -Stream "%STREAM%" -Fps %FPS% -MaxWidth %MAXW% -Duration %DUR%
endlocal