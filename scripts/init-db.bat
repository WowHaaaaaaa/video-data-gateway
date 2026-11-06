@echo off
REM One-click: create the video_gateway database from backend\sql\00_init_all.sql
REM WARNING: this script DROPs and recreates all tables (existing data will be lost).
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-db.ps1" %*
endlocal