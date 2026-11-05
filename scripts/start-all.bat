@echo off
REM Start the whole stack: MySQL -> ZLMediaKit -> Gateway, each in its own window.
setlocal
echo ============================================
echo  Starting video data gateway stack ...
echo ============================================
start "VGW-MySQL" cmd /k "%~dp0start-mysql.bat"
echo  waiting for MySQL to be ready (8s) ...
timeout /t 8 /nobreak >nul
start "VGW-ZLMediaKit" cmd /k "%~dp0start-zlm.bat"
start "VGW-Gateway" cmd /k "%~dp0start-gateway.bat"
echo.
echo  Done. Three windows are opening:
echo    1) MySQL        : port 3307
echo    2) ZLMediaKit   : port 80 / 554 / 1935
echo    3) Gateway      : http://127.0.0.1:8088/doc.html
echo.
echo  Push a test stream : scripts\push-test.bat cam01
echo  Open player page   : http://127.0.0.1/player.html
endlocal
