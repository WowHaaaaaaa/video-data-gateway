param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Stream = "usbcam"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ff = Join-Path $root "tools\ffmpeg\bin\ffmpeg.exe"
if (-not (Test-Path $ff)) { $ff = Join-Path $root "tools\ffmpeg\package\ffmpeg.exe" }

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Camera  : $Device"
Write-Host "  Push to : rtmp://127.0.0.1/live/$Stream"
Write-Host "  Play    : http://127.0.0.1/player.html  (stream id = $Stream)"
Write-Host "  Ctrl+C to stop"
Write-Host "========================================" -ForegroundColor Cyan

& $ff -hide_banner -loglevel warning -f dshow -i "video=$Device" `
    -c:v libx264 -preset ultrafast -tune zerolatency -g 25 -pix_fmt yuv420p `
    -f flv "rtmp://127.0.0.1/live/$Stream"
Write-Host "Push finished." -ForegroundColor Yellow
