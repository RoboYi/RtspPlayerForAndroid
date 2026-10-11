[CmdletBinding()]
param([string]$AndroidSdkPath = '', [switch]$SkipBuild)
# Optional publisher workflow; never invoked by Demo Gradle builds.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    $taskKeyCheck = & java tools/VerifyReleaseSigning.java $taskRoot
    if ($LASTEXITCODE -ne 0) { throw 'Release signing configuration is not ready.' }
    $taskKeyCheck | Write-Output
    $taskExpectedCertificate = (([string]($taskKeyCheck | Where-Object { $_ -match '^Certificate SHA-256:' } | Select-Object -First 1)) -replace '^.*SHA-256:\s*', '').Trim()
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    }
    if (-not $AndroidSdkPath) { $AndroidSdkPath = $env:ANDROID_SDK_ROOT }
    if (-not $AndroidSdkPath) {
        $taskSdkLine = Get-Content -LiteralPath local.properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($taskSdkLine) { $AndroidSdkPath = $taskSdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    if (-not $AndroidSdkPath) { throw 'Pass -AndroidSdkPath or configure local.properties.' }
    $taskTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdkPath 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $taskTools) { throw 'Android SDK build-tools not found.' }
    $taskGradle = Get-Content -LiteralPath app/build.gradle -Raw
    $taskVersion = [regex]::Match($taskGradle, "versionName\s+'([^']+)'").Groups[1].Value
    $taskVersionCode = [regex]::Match($taskGradle, 'versionCode\s+(\d+)').Groups[1].Value
    $taskPackage = [regex]::Match($taskGradle, "applicationId\s+'([^']+)'").Groups[1].Value
    $taskApk = Join-Path $taskRoot "app/build/outputs/apk/release/RoboYiPlayer-$taskVersion.apk"
    $taskSignature = & java -jar (Join-Path $taskTools.FullName 'lib/apksigner.jar') verify --verbose --print-certs $taskApk
    if ($LASTEXITCODE -ne 0 -or $taskSignature -match 'CN=Android Debug') { throw 'APK release signature verification failed.' }
    $taskActualCertificate = (([string]($taskSignature | Where-Object { $_ -match '^(Signer #1|V[0-9.]+ Signer): certificate SHA-256 digest:' } | Select-Object -First 1)) -replace '^.*digest:\s*', '').Trim()
    if ($taskActualCertificate -ne $taskExpectedCertificate) { throw 'APK does not use the configured release certificate.' }
    $taskBadging = & (Join-Path $taskTools.FullName 'aapt.exe') dump badging $taskApk
    if ($LASTEXITCODE -ne 0 -or $taskBadging -contains 'application-debuggable') { throw 'APK metadata inspection failed or APK is debuggable.' }
    $taskPackageLine = $taskBadging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
    if (-not ($taskPackageLine.Contains("name='$taskPackage'") -and $taskPackageLine.Contains("versionCode='$taskVersionCode'") -and $taskPackageLine.Contains("versionName='$taskVersion'"))) { throw 'Unexpected APK package or version.' }
    $taskAlignment = & (Join-Path $taskTools.FullName 'zipalign.exe') -c -P 16 -v 4 $taskApk
    if ($LASTEXITCODE -ne 0) { throw 'APK ZIP alignment verification failed.' }
    & python tools/check_native_page_size.py $taskApk
    if ($LASTEXITCODE -ne 0) { throw 'Native ELF page-size verification failed.' }
    $taskPolicyHash = (Get-FileHash -LiteralPath app/src/main/assets/legal/privacy-policy.html).Hash
    if ($taskPolicyHash -ne (Get-FileHash -LiteralPath docs/privacy/index.html).Hash) { throw 'Bundled and web privacy policy texts differ.' }
    $taskArchive = [System.IO.Compression.ZipFile]::OpenRead($taskApk)
    try {
        $taskEntry = $taskArchive.GetEntry('assets/legal/privacy-policy.html')
        if (-not $taskEntry) { throw 'APK does not contain the privacy policy.' }
        $taskStream = $taskEntry.Open()
        $taskHasher = [System.Security.Cryptography.SHA256]::Create()
        try { $taskBundledHash = ([BitConverter]::ToString($taskHasher.ComputeHash($taskStream))).Replace('-', '') }
        finally { $taskStream.Dispose(); $taskHasher.Dispose() }
        if ($taskBundledHash -ne $taskPolicyHash) { throw 'APK contains a stale privacy policy.' }
    } finally { $taskArchive.Dispose() }
    New-Item -ItemType Directory -Path app/release -Force | Out-Null
    $taskDestination = Join-Path $taskRoot "app/release/RoboYiPlayer-$taskVersion-release.apk"
    Copy-Item -LiteralPath $taskApk -Destination $taskDestination -Force
    $taskHash = (Get-FileHash -LiteralPath $taskDestination).Hash
    $taskReport = [ordered]@{
        checkedAt = (Get-Date).ToString('o'); apk = (Split-Path -Leaf $taskDestination)
        package = $taskPackage; versionName = $taskVersion; versionCode = [int]$taskVersionCode
        sha256 = $taskHash; bytes = (Get-Item -LiteralPath $taskDestination).Length
        certificateSha256 = $taskActualCertificate; privacyPolicySha256 = $taskPolicyHash
        signatureVerified = $true; debuggable = $false; nativeElf16KbVerified = $true; apkZip16KbVerified = $true
        sdkArtifacts = @(Get-ChildItem -LiteralPath app/libs -Filter '*.aar' | ForEach-Object { @{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash } })
    }
    $taskReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "app/release/RoboYiPlayer-$taskVersion-release-checks.json" -Encoding UTF8
    Write-Host "Release APK: $taskDestination"
    Write-Host "SHA-256: $taskHash"
} finally { Pop-Location }
