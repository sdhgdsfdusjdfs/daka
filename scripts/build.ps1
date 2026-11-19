param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$SdkRoot = "$PSScriptRoot\..\.tools\sdk",
    [string]$GradleHome = "",
    [switch]$UseMirror,
    [string]$Proxy = ""
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath "$PSScriptRoot\..").Path
if (!$JdkHome -or !(Test-Path -LiteralPath "$JdkHome\bin\java.exe")) { throw '请通过 -JdkHome 指定 JDK 17 或 21。' }
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkRoot).Path
$env:ANDROID_USER_HOME = "$projectRoot\.tools\android-user"
$env:GRADLE_USER_HOME = "$projectRoot\.tools\gradle-home"
if ($UseMirror) { $env:DAKA_USE_MIRROR = 'true' }
if ($Proxy) {
    $proxyUri = [Uri]$Proxy
    $env:GRADLE_OPTS = "-Dhttp.proxyHost=$($proxyUri.Host) -Dhttp.proxyPort=$($proxyUri.Port) -Dhttps.proxyHost=$($proxyUri.Host) -Dhttps.proxyPort=$($proxyUri.Port) -Dorg.gradle.internal.http.connectionTimeout=15000 -Dorg.gradle.internal.http.socketTimeout=30000"
}
$sdkPath = $env:ANDROID_HOME.Replace('\','/')
Set-Content -LiteralPath "$projectRoot\local.properties" -Value "sdk.dir=$sdkPath" -Encoding ascii
$signingDir = "$projectRoot\.tools\signing"
New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
$credentialsFile = "$signingDir\credentials.json"
if (!(Test-Path -LiteralPath $credentialsFile)) {
    $randomBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($randomBytes); $rng.Dispose()
    @{ password = [Convert]::ToBase64String($randomBytes) } | ConvertTo-Json | Set-Content -LiteralPath $credentialsFile -Encoding utf8
}
$credentials = Get-Content -LiteralPath $credentialsFile -Raw | ConvertFrom-Json
$env:DAKA_STORE_PASSWORD = $credentials.password
$env:DAKA_KEYSTORE = "$signingDir\daka-release.jks"
if (!(Test-Path -LiteralPath $env:DAKA_KEYSTORE)) {
    & "$JdkHome\bin\keytool.exe" -genkeypair -keystore $env:DAKA_KEYSTORE -storetype PKCS12 -storepass:env DAKA_STORE_PASSWORD -keypass:env DAKA_STORE_PASSWORD -alias daka -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Daka Local Release'
    if ($LASTEXITCODE -ne 0) { throw '签名密钥生成失败' }
}
Push-Location $projectRoot
try {
    $gradle = if ($GradleHome) { "$GradleHome\bin\gradle.bat" } else { "$projectRoot\gradlew.bat" }
    & $gradle --no-daemon testDebugUnitTest lintDebug assembleRelease
    if ($LASTEXITCODE -ne 0) { throw '构建或检查失败，请查看上方输出。' }
    New-Item -ItemType Directory -Path "$projectRoot\releases" -Force | Out-Null
    $apk = "$projectRoot\releases\daka-0.1.0.apk"
    Copy-Item -LiteralPath "$projectRoot\app\build\outputs\apk\release\app-release.apk" -Destination $apk -Force
    & "$SdkRoot\build-tools\35.0.0\apksigner.bat" verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败' }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$projectRoot\releases\SHA256SUMS.txt" -Value "$hash  daka-0.1.0.apk" -Encoding ascii
    Write-Output "完成：$apk"
} finally {
    Remove-Item Env:DAKA_STORE_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
