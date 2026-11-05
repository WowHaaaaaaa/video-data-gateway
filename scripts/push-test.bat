@echo off
REM Push a test stream to ZLMediaKit.
REM Usage: push-test.bat [streamId] [durationSec]
setlocal
set STREAM=%~1
if "%STREAM%"=="" set STREAM=fakesrc
set DURATION=%~2
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-test.ps1" -Stream "%STREAM%" -Duration "%DURATION%"
endlocal
