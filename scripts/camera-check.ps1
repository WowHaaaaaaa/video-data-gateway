param(
    [switch]$Push,
    [string]$Stream = "usbcam"
)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$ff = Join-Path $root "tools\ffmpeg\bin\ffmpeg.exe"
if (-not (Test-Path $ff)) { $ff = Join-Path $root "tools\ffmpeg\package\ffmpeg.exe" }
if (-not (Test-Path $ff)) { Write-Host "[ERR] ffmpeg not found" -ForegroundColor Red; exit 1 }

Write-Host "==================== 1) ffmpeg 枚举到的设备 ====================" -ForegroundColor Cyan
$o = & $ff -hide_banner -list_devices true -f dshow -i dummy 2>&1
$lines = $o | ForEach-Object { $_.ToString() }
$lines | Where-Object { $_ -match 'DirectShow|^\[dshow.*"  |^\[dshow.*\{' } | ForEach-Object { $_.ToString() }

$found = @()
$inVideo = $false
foreach ($l in $lines) {
    $s = $l.ToString()
    if ($s -match 'DirectShow video devices') { $inVideo = $true; continue }
    if ($s -match 'DirectShow audio devices') { $inVideo = $false; continue }
    # 只收视频段里的"真名字"，跳过 Alternative name（GUID 能打开但不适合当展示名）
    if ($inVideo -and $s -notmatch 'Alternative name' -and $s -match '"([^"]+)"\s*$') {
        $found += $Matches[1]
    }
}
if ($found.Count -eq 0) { Write-Host "  (未枚举到视频设备)" -ForegroundColor Yellow }

# 已知设备（即使枚举不到也试一下）
$candidates = @()
$candidates += $found
foreach ($n in @("Integrated Camera", "RGB Camera", "Integrated IR Camera")) {
    if ($candidates -notcontains $n) { $candidates += $n }
}

Write-Host ""
Write-Host "==================== 2) 逐个尝试打开 (每个 2 秒) ====================" -ForegroundColor Cyan
$good = @()
foreach ($c in $candidates) {
    Write-Host -NoNewline ("  尝试 [{0}] ... " -f $c)
    $t = & $ff -hide_banner -loglevel error -f dshow -i "video=$c" -t 2 -f null - 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "OK 可用" -ForegroundColor Green
        $good += $c
    } else {
        $msg = (($t | ForEach-Object { $_.ToString() }) -join ' ')
        if ($msg -match 'Unable to BindToObject') { Write-Host "失败: 设备被占用或不可访问" -ForegroundColor Red }
        elseif ($msg -match 'Could not find video device') { Write-Host "失败: 系统里没有这个名字的设备" -ForegroundColor DarkGray }
        else { Write-Host ("失败: " + $msg.Substring(0, [Math]::Min(90, $msg.Length))) -ForegroundColor Red }
    }
}

Write-Host ""
if ($good.Count -eq 0) {
    Write-Host "==== 结论：当前没有可用摄像头 ====" -ForegroundColor Yellow
    Write-Host "请依次检查：" -ForegroundColor Yellow
    Write-Host "  1. 关闭 相机App / 微信视频 / 腾讯会议 / WeLink / Zoom / 浏览器里的视频通话页面"
    Write-Host "  2. 设置 - 隐私和安全性 - 相机：确认【允许应用访问相机】和【允许桌面应用访问相机】都打开"
    Write-Host "  3. 联想笔记本：检查是否按了相机隐私开关（Fn+F8 或侧边防窥开关），或关掉 Lenovo Vantage 的【智能感知/人走锁屏】"
    Write-Host "  4. 设置 - 账户 - 登录选项：可临时移除【面部识别】，它会占用红外相机"
    Write-Host "  5. 外接 USB 相机：换个 USB 口重新插拔"
    Write-Host "做完第 1 步后重新双击本脚本即可。"
    exit 2
}

Write-Host ("==== 可用设备: " + ($good -join ", ")) -ForegroundColor Green

if ($Push) {
    $dev = $good[0]
    Write-Host ""
    Write-Host ("==== 开始推流: [{0}] -> rtmp://127.0.0.1/live/{1} ====" -f $dev, $Stream) -ForegroundColor Cyan
    Write-Host "按 Ctrl+C 停止"
    & $ff -hide_banner -loglevel warning -f dshow -i "video=$dev" `
        -c:v libx264 -preset ultrafast -tune zerolatency -g 25 -pix_fmt yuv420p `
        -f flv "rtmp://127.0.0.1/live/$Stream"
} else {
    Write-Host ""
    Write-Host "推流的命令（把 <设备名> 换成上面可用的那个）：" -ForegroundColor Cyan
    Write-Host ("  scripts\push-camera.bat `"{0}`" {1}" -f $good[0], $Stream) -ForegroundColor White
}
