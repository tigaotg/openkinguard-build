# OpenKinGuard local Android test build. Never stores or prints the signing password.
# Run from PowerShell inside the extracted source directory:
#   powershell -ExecutionPolicy Bypass -File .\Build-PilotV2-Windows.ps1
$ErrorActionPreference = 'Stop'
$keyFile = Join-Path $PSScriptRoot 'private-signing\openkinguard-new-pilot-v2.p12'
$wrapper = Join-Path $PSScriptRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $keyFile -PathType Leaf)) {
    throw '缺少专属 V2 签名文件。请先把备份的 openkinguard-new-pilot-v2.p12 放到 private-signing 目录。'
}
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw '找不到 Gradle Wrapper，源码 ZIP 可能未完整解压。'
}

$secret = Read-Host '输入 V2 签名密码（不会在屏幕显示）' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $env:OKG_PILOT_V2_SIGNING_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    $secret.Dispose()
}

try {
    Push-Location $PSScriptRoot
    try {
        & $wrapper ':app:testDebugUnitTest' ':app:assemblePilotV2' '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw 'Gradle 编译或测试失败；没有产生可确认的新 APK。' }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:\OKG_PILOT_V2_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}

$outputDir = Join-Path $PSScriptRoot 'app\build\outputs\apk\pilotV2'
$apk = Get-ChildItem -LiteralPath $outputDir -Filter '*.apk' -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $apk) { throw 'Gradle 返回成功，但未在 pilotV2 输出目录找到 APK。' }
Write-Host ('编译完成：' + $apk.FullName)
Write-Host ('SHA-256：' + (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash)
Write-Host '这是独立 V2 包名，不会覆盖旧测试版或迁移原有绑定关系。'
