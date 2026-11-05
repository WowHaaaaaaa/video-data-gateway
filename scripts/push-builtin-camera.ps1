param(
    [string]$Stream = "incam"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ff = Join-Path $root "tools\ffmpeg\bin\ffmpeg.exe"
if (-not (Test-Path $ff)) { $ff = Join-Path $root "tools\ffmpeg\package\ffmpeg.exe" }
if (-not (Test-Path $ff)) { Write-Host "[ERR] ffmpeg not found" -ForegroundColor Red; exit 1 }

$Device = "Integrated Camera"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Camera  : $Device (built-in)"
Write-Host "  Push to : rtmp://127.0.0.1/live/$Stream"
Write-Host "  Play    : http://127.0.0.1:8088/  -> 实时视频 -> 内置摄像头"
Write-Host "  Ctrl+C to stop"
Write-Host "========================================" -ForegroundColor Cyan

& $ff -hide_banner -loglevel warning -f dshow -i "video=$Device" `
    -c:v libx264 -preset ultrafast -tune zerolatency -g 25 -pix_fmt yuv420p `
    -f flv "rtmp://127.0.0.1/live/$Stream"

Write-Host "Push finished." -ForegroundColor Yellow
