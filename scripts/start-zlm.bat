@echo off
REM Start ZLMediaKit streaming media server (foreground; close window to stop).
setlocal
cd /d "%~dp0..\tools\zlm"
if not exist MediaServer.exe (
    echo [ERROR] MediaServer.exe not found. Run: node scripts\fetch-deps.mjs zlm
    pause
    exit /b 1
)
echo ========================================
echo  ZLMediaKit starting...
echo  HTTP/FLV/HLS : http://127.0.0.1
echo  RTSP         : rtsp://127.0.0.1:554/live/cam01
echo  RTMP         : rtmp://127.0.0.1/live/cam01
echo  Player page  : http://127.0.0.1/player.html
echo  Press Ctrl+C to stop
echo ========================================
MediaServer.exe
endlocal
