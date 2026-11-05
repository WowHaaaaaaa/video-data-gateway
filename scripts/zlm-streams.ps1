param(
    [string]$Secret = 'a8Fk3QzR7mWp2TvN6xLc9Yb4Hs1Jd5Eg',
    [string]$Zlm = 'http://127.0.0.1'
)
$ErrorActionPreference = 'Continue'

Write-Host '==================== ZLMediaKit 在线流 ====================' -ForegroundColor Cyan

try {
    # 用 Invoke-RestMethod：它自己解析 JSON，避免 PowerShell 把多行 JSON 拆碎的问题
    $resp = Invoke-RestMethod -Uri "$Zlm/index/api/getMediaList?secret=$Secret" -TimeoutSec 8
} catch {
    Write-Host "[错误] 连不上 ZLM：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "请确认 MediaServer.exe 在运行（scripts\start-zlm.bat）" -ForegroundColor Yellow
    exit 1
}

if ($resp.code -ne 0) {
    Write-Host "[错误] ZLM 返回 code=$($resp.code) msg=$($resp.msg)" -ForegroundColor Red
    Write-Host "多半是 secret 不对：检查 tools\zlm\config.ini 的 [api] secret" -ForegroundColor Yellow
    exit 1
}

if (-not $resp.data) {
    Write-Host '当前没有任何在线流。' -ForegroundColor Yellow
    Write-Host '推一路流试试：' -ForegroundColor Yellow
    Write-Host '  相机:  scripts\push-usb-camera.bat' -ForegroundColor Gray
    Write-Host '  假流:  scripts\push-test.bat cam01' -ForegroundColor Gray
    exit 0
}

# 只保留"源流"：originType=1 推流进来，4 拉流代理进来。
# 其余（2 转协议、3 录制）是同一路流的派生，去重后不展示。
$sources = $resp.data | Where-Object { $_.originType -in 1, 4 }

# 用 @() 包一层：PowerShell 5.1 里单个对象没有 .Count，包成数组后才稳定
$table = @($sources | Group-Object { "$($_.app)/$($_.stream)" } | ForEach-Object {
    $first = $_.Group[0]                                        # 取该流的第一条（各协议 tracks 相同）
    $v = $first.tracks | Where-Object { $_.codec_type -eq 0 } | Select-Object -First 1
    $a = $first.tracks | Where-Object { $_.codec_type -eq 1 } | Select-Object -First 1
    [pscustomobject]@{
        '流'       = "$($first.app)/$($first.stream)"
        '来源'     = $first.originTypeStr
        '分辨率'   = if ($v) { "$($v.width)x$($v.height)" } else { '-' }
        '帧率'     = if ($v) { $v.fps } else { '-' }
        '编码'     = if ($v) { $v.codec_id_name } else { '-' }
        '音频'     = if ($a) { "$([math]::Round($a.sample_rate / 1000, 1))kHz" } else { '无' }
        '存活(秒)' = $first.aliveSecond
        '协议数'   = $_.Group.Count
        '观看人数' = ($_.Group | Measure-Object -Property readerCount -Maximum).Maximum
    }
})

$table | Format-Table -AutoSize

Write-Host ("共 {0} 路源流（{1} 条协议记录，ZLM 会把每路流转成多种协议）" -f $table.Count, $sources.Count) -ForegroundColor DarkGray
Write-Host ''
Write-Host '播放地址：' -ForegroundColor DarkGray
foreach ($row in $table) {
    $key = $row.'流'
    Write-Host ("  {0,-16} FLV: http://127.0.0.1/{1}.live.flv   RTSP: rtsp://127.0.0.1:554/{1}" -f $key, $key) -ForegroundColor DarkGray
}
