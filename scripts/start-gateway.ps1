param([string]$ExtraArgs = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$jar = Join-Path $root "backend\target\video-data-gateway.jar"
if (-not (Test-Path $jar)) {
    Write-Host "[ERROR] 未找到 jar，请先构建：" -ForegroundColor Red
    Write-Host "  scripts\build-backend.bat"
    exit 1
}
$config = Join-Path $root "backend\src\main\resources\application.yml"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  视频数据网关 启动中..."
Write-Host "  前端界面 : http://127.0.0.1:8088/"
Write-Host "  接口文档 : http://127.0.0.1:8088/doc.html"
Write-Host "  健康检查 : http://127.0.0.1:8088/api/v1/health"
Write-Host "  前端目录 : $root\frontend  (改前端不用重启)"
Write-Host "========================================" -ForegroundColor Cyan

# 必须从项目根目录启动：application.yml 里静态资源写的是 file:./frontend/
Push-Location $root
try {
    if (Test-Path $config) {
        & java -jar $jar "--spring.config.additional-location=file:$config" $ExtraArgs
    } else {
        & java -jar $jar $ExtraArgs
    }
} finally {
    Pop-Location
}
