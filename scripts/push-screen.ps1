param(
    [string]$Stream = "screencast",
    [int]$Fps = 15,
    [int]$MaxWidth = 0,
    [int]$Duration = 0,
    [string]$Region = "",
    [string]$Server = "127.0.0.1"
)

# 屏幕捕获推流（Windows gdigrab）
#
# 说明：
#   · 用 ffmpeg 的 gdigrab 设备直接抓 Windows 桌面，编码成 H.264 后以 RTMP 推到 ZLMediaKit。
#   · 推上去之后，平台里那个 streamApp/streamId = live/<Stream> 的通道会自动变「推流中」。
#   · 前提：平台上必须先登记这条通道并启用，否则 on_publish 会判定"未注册的流"直接拒绝。

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$ffmpeg = Join-Path $root "tools\ffmpeg\bin\ffmpeg.exe"
if (-not (Test-Path $ffmpeg)) {
    $ffmpeg = Join-Path $root "tools\ffmpeg\package\ffmpeg.exe"
}
if (-not (Test-Path $ffmpeg)) {
    Write-Host "[ERROR] 没找到 ffmpeg" -ForegroundColor Red
    Write-Host "        先执行: node scripts\fetch-deps.mjs ffmpeg" -ForegroundColor Yellow
    exit 1
}

if ($Fps -lt 1 -or $Fps -gt 60) {
    Write-Host "[ERROR] -Fps 建议 5~30（屏幕内容变化不快的场景 10~15 足够）" -ForegroundColor Red
    exit 1
}

$pushUrl = "rtmp://$Server/live/$Stream"

# ---------- 输入：桌面捕获 ----------
$inArgs = @("-f", "gdigrab", "-framerate", "$Fps")
$regionDesc = "整个桌面"

if ($Region) {
    $part = $Region.Split(',')
    if ($part.Count -ne 4) {
        Write-Host "[ERROR] -Region 格式应为: 起点X,起点Y,宽,高   例如 0,0,1280,720" -ForegroundColor Red
        exit 1
    }
    $rx = $part[0].Trim(); $ry = $part[1].Trim(); $rw = $part[2].Trim(); $rh = $part[3].Trim()
    $inArgs += @("-offset_x", $rx, "-offset_y", $ry, "-video_size", "${rw}x${rh}")
    $regionDesc = "区域 ${rw}x${rh}（起点 $rx,$ry）"
}
$inArgs += @("-i", "desktop")

# ---------- 输出：实时 H.264 + RTMP ----------
# -pix_fmt yuv420p 必须有：gdigrab 输出 bgr0，不转成 yuv420p 浏览器/flv.js 播不出来
# -rtbufsize 100M  不加容易报 real-time buffer too full（尤其高分辨率/多屏）
$outArgs = @(
    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
    "-pix_fmt", "yuv420p", "-g", "$Fps", "-rtbufsize", "100M"
)
$scaleDesc = "不缩放（原始分辨率）"
if ($MaxWidth -gt 0) {
    $outArgs += @("-vf", "scale=${MaxWidth}:-2")
    $scaleDesc = "宽度缩到 $MaxWidth（高度按比例，自动取偶数）"
}
$limitDesc = "不限时长（Ctrl+C 停止）"
if ($Duration -gt 0) {
    $outArgs += @("-t", "$Duration")
    $limitDesc = "$Duration 秒后自动停止"
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  屏幕捕获推流（Windows gdigrab）" -ForegroundColor Cyan
Write-Host "  源        : $regionDesc"
Write-Host "  推送至    : $pushUrl"
Write-Host "  帧率      : $Fps fps"
Write-Host "  画面      : $scaleDesc"
Write-Host "  时长      : $limitDesc"
Write-Host "  前提      : 平台已登记并启用通道 live/$Stream" -ForegroundColor Yellow
Write-Host "  看画面    : http://$Server/player.html  （app=live, stream=$Stream）"
Write-Host "             或平台 -> 实时视频"
Write-Host "  查状态    : scripts\zlm-streams.bat"
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

& $ffmpeg -hide_banner -loglevel warning @inArgs @outArgs -f flv $pushUrl

Write-Host ""
Write-Host "推流结束（会话已结算，通道会回到离线）" -ForegroundColor Yellow