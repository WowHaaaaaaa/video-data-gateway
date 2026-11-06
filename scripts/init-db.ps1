param(
    [string]$Port     = "3307",
    [string]$User     = "root",
    [string]$Password = "123456",
    [string]$SqlFile  = "",          # 默认 backend\sql\00_init_all.sql
    [switch]$Force                    # 跳过"库里已有数据"的确认
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# PowerShell 5.1 会把原生命令的 stderr 当成错误记录，配合 Stop 会直接抛异常。
# mysql.exe 每次都会往 stderr 写一行 "Using a password ..." 警告，所以这里统一包一层。
function Invoke-Mysql {
    param([string]$Exe, [string[]]$MysqlArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $out = & $Exe @MysqlArgs 2>&1 | Where-Object { $_ -notmatch 'Using a password' }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return [PSCustomObject]@{ Output = @($out); Code = $code }
}

if ([string]::IsNullOrWhiteSpace($SqlFile)) {
    $SqlFile = Join-Path $root "backend\sql\00_init_all.sql"
}
if (-not (Test-Path $SqlFile)) {
    Write-Host "[ERROR] 找不到 SQL 文件：$SqlFile" -ForegroundColor Red
    exit 1
}

# ---------- 1. 找 mysql.exe（项目自带的免安装版）----------
$mysqlExe = Get-ChildItem (Join-Path $root "tools\mysql") -Recurse -Filter "mysql.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
if (-not $mysqlExe) {
    Write-Host "[ERROR] 找不到 tools\mysql\...\bin\mysql.exe" -ForegroundColor Red
    Write-Host "        先执行：node scripts\fetch-deps.mjs mysql" -ForegroundColor Yellow
    exit 1
}

# ---------- 2. 确认 MySQL 在监听 ----------
function Test-Port([string]$host_, [int]$port_) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect($host_, $port_); return $true } catch { return $false } finally { $client.Dispose() }
}
if (-not (Test-Port "127.0.0.1" ([int]$Port))) {
    Write-Host "[ERROR] 127.0.0.1:$Port 没有监听，MySQL 没起来" -ForegroundColor Red
    Write-Host "        先执行：scripts\start-mysql.bat" -ForegroundColor Yellow
    exit 1
}

# ---------- 3. 危险提示：这个脚本会 DROP TABLE ----------
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  视频数据网关 · 数据库初始化"
Write-Host "  SQL  : $SqlFile"
Write-Host "  连接 : 127.0.0.1:$Port  $User"
Write-Host "  警告 : 每张表都是 DROP TABLE IF EXISTS 再建，会把现有数据清空！"
Write-Host "========================================" -ForegroundColor Cyan

$probe = Invoke-Mysql $mysqlExe @("--port=$Port", "-u$User", "-p$Password", "-N", "-e",
    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='video_gateway';")
$existing = ($probe.Output | Select-Object -First 1)
if ($existing -and [int]$existing -gt 0 -and -not $Force) {
    Write-Host ""
    Write-Host "  [注意] 库里已经有 $existing 张表，继续执行会清空其中的数据。" -ForegroundColor Yellow
    $ans = Read-Host "  确认继续？输入 yes 回车（其它任意键取消）"
    if ($ans -ne "yes") {
        Write-Host "  已取消，什么都没改。" -ForegroundColor Yellow
        exit 0
    }
}

# ---------- 4. 执行 ----------
Write-Host ""
Write-Host "  正在执行……" -ForegroundColor Cyan
$sqlPath = $SqlFile -replace '\\', '/'
$run = Invoke-Mysql $mysqlExe @("--port=$Port", "-u$User", "-p$Password",
    "--default-character-set=utf8mb4", "-e", "source $sqlPath")
if ($run.Code -ne 0) {
    Write-Host "[FAILED] 执行失败：" -ForegroundColor Red
    $run.Output | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit $run.Code
}
$run.Output | ForEach-Object { Write-Host "  $_" }

Write-Host ""
Write-Host "  ✔ 初始化完成" -ForegroundColor Green
Write-Host "    下一步：scripts\start-all.bat  然后打开 http://127.0.0.1:8088/" -ForegroundColor Green
Write-Host "    界面登录用的 api-key：vwg_console（超管，全部权限）" -ForegroundColor Green