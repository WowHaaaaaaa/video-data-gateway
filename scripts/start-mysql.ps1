param()
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$mysqlBase = Join-Path $root "tools\mysql\mysql-8.0.28-winx64"

if (-not (Test-Path (Join-Path $mysqlBase "bin\mysqld.exe"))) {
    Write-Host "[ERROR] mysqld not found. Run: node scripts\fetch-deps.mjs mysql" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------
# 关键：mysqld 无法处理含中文的路径（会把路径截断成 C:\Users\data\）。
# 解决：在纯 ASCII 的临时目录建一个目录联接（junction）指向项目里的 MySQL，
#       mysqld 只见 ASCII 路径，真实数据仍保存在项目目录中。
# ---------------------------------------------------------------
$link = Join-Path $env:TEMP "vgwmysql"
if (-not (Test-Path (Join-Path $link "bin\mysqld.exe"))) {
    if (Test-Path $link) { cmd /c "rmdir `"$link`"" 2>$null | Out-Null }
    cmd /c "mklink /J `"$link`" `"$mysqlBase`"" | Out-Null
    if (-not (Test-Path (Join-Path $link "bin\mysqld.exe"))) {
        Write-Host "[ERROR] failed to create junction: $link" -ForegroundColor Red
        exit 1
    }
}

$running = netstat -ano | Select-String ":3307\s+.*LISTENING"
if ($running) {
    Write-Host "MySQL already listening on 3307, skip start." -ForegroundColor Yellow
    exit 0
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  MySQL 8.0.28 starting on port 3307"
Write-Host "  (3306 is used by the company-installed MySQL service)"
Write-Host "  data dir : $link\data"
Write-Host "  user/pwd : root / 123456"
Write-Host "  Press Ctrl+C to stop"
Write-Host "========================================" -ForegroundColor Cyan

& (Join-Path $link "bin\mysqld.exe") --basedir="$link" --datadir="$link\data" --port=3307 --console
