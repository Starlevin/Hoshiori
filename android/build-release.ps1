param([switch]$SkipBuild, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $SkipBuild) {
    & "$PSScriptRoot/gradlew.bat" -p $PSScriptRoot -g "$projectRoot/.gradle-home" --no-daemon --max-workers=2 :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release build/checks failed.' }
}
$apkDir = "$PSScriptRoot/app/build/outputs/apk/release"
$metadata = Get-Content -Raw "$apkDir/output-metadata.json" | ConvertFrom-Json
if ($metadata.applicationId -ne 'com.glasssign.app' -or $metadata.variantName -ne 'release') { throw 'Expected production Release APK.' }
$version = $metadata.elements[0].versionName
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Expected final release version.' }
if (-not $OutputDirectory) { $OutputDirectory = "$projectRoot/outputs/Hoshiori-v$version-release" }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$apk = Join-Path $OutputDirectory "hoshiori-v$version-release.apk"
if (Test-Path -LiteralPath $apk) { throw 'Signed artifact already exists; preserve it and choose a new output directory.' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildTools = Join-Path $sdk 'build-tools/35.0.0'
$signDir = Join-Path $env:LOCALAPPDATA 'Hoshiori/signing'
$keystore = Join-Path $signDir 'hoshiori-release.jks'
$passwordFile = Join-Path $signDir 'release-password.dpapi'
if (-not (Test-Path $keystore) -or -not (Test-Path $passwordFile)) { throw 'Existing Hoshiori release signing material is required on this machine.' }
& "$buildTools/zipalign.exe" -p -f 4 "$apkDir/app-release-unsigned.apk" $apk
if ($LASTEXITCODE -ne 0) { throw 'Alignment failed.' }
$securePass = (Get-Content -Raw -LiteralPath $passwordFile).Trim() | ConvertTo-SecureString
$env:HOSHIORI_RELEASE_STOREPASS = [System.Net.NetworkCredential]::new('', $securePass).Password
try {
    & "$buildTools/apksigner.bat" sign --ks $keystore --ks-key-alias hoshiori-release --ks-pass env:HOSHIORI_RELEASE_STOREPASS --key-pass env:HOSHIORI_RELEASE_STOREPASS $apk
    if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
} finally { Remove-Item Env:HOSHIORI_RELEASE_STOREPASS -ErrorAction SilentlyContinue }
$signature = & "$buildTools/apksigner.bat" verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
if (-not ($signature -match '6c4e3dcec3af2aa6c5ba4b69083f8c1ac56aeee1acdd60c2d57146725ef65369')) { throw 'Release signer differs from v0.7.5.' }
$signature | Set-Content -Encoding utf8 (Join-Path $OutputDirectory 'SIGNATURE.txt')
& "$buildTools/zipalign.exe" -c -p 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'Signed alignment verification failed.' }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $(Split-Path $apk -Leaf)" | Set-Content -Encoding utf8 "$apk.sha256"
Write-Output "Signed Release APK: $apk"
Write-Output "SHA-256: $hash"
