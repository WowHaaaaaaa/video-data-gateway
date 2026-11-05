param(
    [string]$Stream = "fakesrc",
    [int]$Duration = 0
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ffmpeg = Join-Path $root "tools\ffmpeg\bin\ffmpeg.exe"
if (-not (Test-Path $ffmpeg)) {
    $ffmpeg = Join-Path $root "tools\ffmpeg\package\ffmpeg.exe"
}
if (-not (Test-Path $ffmpeg)) {
    Write-Host "[ERROR] ffmpeg not found. Run: node scripts\fetch-deps.mjs ffmpeg" -ForegroundColor Red
    exit 1
}

$args = @(
    "-hide_banner", "-loglevel", "warning", "-re",
    "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100"
)
if ($Duration -gt 0) { $args += @("-t", "$Duration") }
$args += @(
    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
    "-g", "25", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "64k", "-ar", "44100",
    "-f", "flv", "rtmp://127.0.0.1/live/$Stream"
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Push URL : rtmp://127.0.0.1/live/$Stream"
if ($Duration -gt 0) { Write-Host "  Duration : $Duration s" }
Write-Host "  Play page: http://127.0.0.1/player.html  (stream id = $Stream)"
Write-Host "  Ctrl+C to stop"
Write-Host "========================================" -ForegroundColor Cyan

& $ffmpeg @args
Write-Host "Push finished." -ForegroundColor Yellow
