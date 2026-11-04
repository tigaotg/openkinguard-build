# OpenKinGuard Pilot V3 local Android build. Never writes or prints the signing password.
# Run from PowerShell inside the extracted source directory:
#   powershell -ExecutionPolicy Bypass -File .\Build-PilotV3-Windows.ps1
$ErrorActionPreference = 'Stop'
$keyFile = Join-Path $PSScriptRoot 'private-signing\openkinguard-pilot-v3.p12'
$wrapper = Join-Path $PSScriptRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $keyFile -PathType Leaf)) {
    throw '缺少 Pilot V3 签名文件。请先将备份的 openkinguard-pilot-v3.p12 放进 private-signing 目录。'
}
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw '找不到 Gradle Wrapper，源码 ZIP 可能没有完整解压。'
}

$secret = Read-Host '输入 Pilot V3 签名密码（屏幕不会显示）' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $env:OKG_PILOT_V3_SIGNING_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    $secret.Dispose()
}

try {
    Push-Location $PSScriptRoot
    try {
        & $wrapper ':app:testDebugUnitTest' ':app:assemblePilotV3' '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw 'Gradle 测试或编译失败。' }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:\OKG_PILOT_V3_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}

$outputDir = Join-Path $PSScriptRoot 'app\build\outputs\apk\pilotV3'
$apk = Get-ChildItem -LiteralPath $outputDir -Filter '*.apk' -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $apk) { throw 'Gradle 返回成功，但没有找到 APK。' }
Write-Host ('编译完成：' + $apk.FullName)
Write-Host ('SHA-256：' + (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash)
Write-Host 'Pilot V3 使用独立包名和签名，不能覆盖旧 Pilot V2，也不会继承旧版绑定。'
