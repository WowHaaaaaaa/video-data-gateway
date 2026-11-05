param([string]$Goal = "package")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$mvn = Join-Path $root "tools\maven\apache-maven-3.9.16\bin\mvn.cmd"
$settings = Join-Path $root "tools\maven-conf\settings.xml"
$pom = Join-Path $root "backend\pom.xml"

if (-not (Test-Path $pom)) {
    Write-Host "[ERROR] 找不到 backend\pom.xml" -ForegroundColor Red
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  构建后端：$Goal"
Write-Host "  pom: backend\pom.xml"
Write-Host "  产物: backend\target\video-data-gateway.jar"
Write-Host "========================================" -ForegroundColor Cyan

& $mvn -s $settings -f $pom -B -DskipTests $Goal
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAILED] 构建失败，退出码 $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

$jar = Join-Path $root "backend\target\video-data-gateway.jar"
if (Test-Path $jar) {
    $mb = [math]::Round((Get-Item $jar).Length / 1MB, 1)
    Write-Host ""
    Write-Host "  ✔ 构建成功：backend\target\video-data-gateway.jar ($mb MB)" -ForegroundColor Green
} else {
    Write-Host "  ⚠ 构建完成但未找到 jar" -ForegroundColor Yellow
}
