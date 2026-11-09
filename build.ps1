[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/build-environment.ps1')
$Classes = Join-Path $BuildRoot 'classes'
$Generated = Join-Path $BuildRoot 'generated'
$Dex = Join-Path $BuildRoot 'dex'
foreach ($Output in @($Classes, $Generated, $Dex)) { Clear-GeneratedDirectory $Output }
$VersionName = Get-BuildSetting 'APP_VERSION_NAME' '2.1.26-source'
$VersionCode = Get-BuildSetting 'APP_VERSION_CODE' '240'
$ApplicationId = Get-BuildSetting 'APP_APPLICATION_ID' 'com.holi.prdbus.opensource'
if ($ApplicationId -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { throw 'Invalid APP_APPLICATION_ID.' }
if ($VersionCode -notmatch '^[1-9][0-9]*$' -or $VersionName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'Invalid application version.' }

# Private data can be supplied by its authorized owner, outside the public tree.
# No private checkout or remote data service is consulted automatically.
$Assets = Join-Path $ProjectRoot 'assets'
$Overlay = Get-BuildSetting 'APP_ASSETS_OVERLAY'
if ($Overlay) {
    $Overlay = Resolve-ProjectPath $Overlay
    if (-not (Test-Path -LiteralPath $Overlay -PathType Container)) { throw 'APP_ASSETS_OVERLAY must be an existing directory.' }
    $StagedAssets = Join-Path $BuildRoot 'staged-assets'
    Clear-GeneratedDirectory $StagedAssets
    foreach ($SourceAssets in @($Assets, $Overlay)) {
        if (Test-Path -LiteralPath $SourceAssets -PathType Container) {
            foreach ($Item in Get-ChildItem -LiteralPath $SourceAssets -Force) { Copy-Item -LiteralPath $Item.FullName -Destination $StagedAssets -Recurse -Force }
        }
    }
    $Assets = $StagedAssets
}
New-Item -ItemType Directory -Force -Path $Assets | Out-Null

$StoreFile = Get-BuildSetting 'RELEASE_STORE_FILE'
$StorePassword = Get-BuildSetting 'RELEASE_STORE_PASSWORD'
$KeyAlias = Get-BuildSetting 'RELEASE_KEY_ALIAS'
$KeyPassword = Get-BuildSetting 'RELEASE_KEY_PASSWORD'
$SigningValues = @($StoreFile, $StorePassword, $KeyAlias, $KeyPassword)
$SigningCount = @($SigningValues | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
if ($SigningCount -ne 0 -and $SigningCount -ne 4) { throw 'Set all four RELEASE_* signing settings, or leave all four unset for a local debug signature.' }
$ReleaseSigning = $SigningCount -eq 4
if ($ReleaseSigning) {
    $StoreFile = Resolve-ProjectPath $StoreFile
    if (-not (Test-Path -LiteralPath $StoreFile -PathType Leaf)) { throw 'Release keystore file not found.' }
} else {
    $SigningDirectory = Join-Path $BuildRoot 'signing'
    New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
    $StoreFile = Join-Path $SigningDirectory 'debug.p12'
    $StorePassword = 'android'
    $KeyPassword = 'android'
    $KeyAlias = 'androiddebugkey'
    if (-not (Test-Path -LiteralPath $StoreFile -PathType Leaf)) {
        & $Keytool '-J-Duser.language=en' '-J-Duser.country=US' -genkeypair -noprompt -keystore $StoreFile -storetype PKCS12 -storepass android -keypass android -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
        Assert-BuildExit 'Debug keystore generation'
    }
}

# Keep the Java/R namespace, but give independently signed builds a separate app ID.
[xml]$Manifest = [IO.File]::ReadAllText((Join-Path $ProjectRoot 'AndroidManifest.xml'))
$AndroidNamespace = 'http://schemas.android.com/apk/res/android'
[void]$Manifest.manifest.application.provider.SetAttribute('authorities', $AndroidNamespace, $ApplicationId + '.update-files')
[void]$Manifest.manifest.application.SetAttribute('debuggable', $AndroidNamespace, $(if ($ReleaseSigning) { 'false' } else { 'true' }))
$ManifestPath = Join-Path $BuildRoot 'AndroidManifest.xml'
$Manifest.Save($ManifestPath)
$Resources = Join-Path $BuildRoot 'resources.zip'
$Unsigned = Join-Path $BuildRoot 'resources-unsigned.apk'
& $Aapt2 compile --dir (Join-Path $ProjectRoot 'res') -o $Resources
Assert-BuildExit 'aapt2 compile'
& $Aapt2 link -I $AndroidJar --manifest $ManifestPath --rename-manifest-package $ApplicationId --custom-package com.holi.prdbus --java $Generated -A $Assets --min-sdk-version 21 --target-sdk-version 35 --version-code $VersionCode --version-name $VersionName -o $Unsigned $Resources
Assert-BuildExit 'aapt2 link'
$Sources = @(
    Get-ChildItem (Join-Path $ProjectRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName
    Get-ChildItem $Generated -Recurse -Filter '*.java' | ForEach-Object FullName
)
& $Javac -encoding UTF-8 -source 8 -target 8 -classpath $AndroidJar -d $Classes $Sources
Assert-BuildExit 'javac'
& (Join-Path $ProjectRoot 'test.ps1')
$ClassesJar = Join-Path $BuildRoot 'classes.jar'
& $Jar cf $ClassesJar -C $Classes '.'
Assert-BuildExit 'jar'
& $D8 --lib $AndroidJar --min-api 21 --output $Dex $ClassesJar
Assert-BuildExit 'd8'
$WithDex = Join-Path $BuildRoot 'with-dex.apk'
Copy-Item -LiteralPath $Unsigned -Destination $WithDex -Force
Push-Location $Dex
try { & $Jar uf $WithDex 'classes.dex'; Assert-BuildExit 'DEX packaging' } finally { Pop-Location }
$Aligned = Join-Path $BuildRoot 'aligned.apk'
& $Zipalign -p -f 4 $WithDex $Aligned
Assert-BuildExit 'zipalign'
$Apk = Join-Path $BuildRoot ("bashi-dehen-$VersionName-signed.apk")
# Pass passwords through the process environment, never command arguments or logs.
$env:BASHI_SIGN_STORE_PASSWORD = $StorePassword
$env:BASHI_SIGN_KEY_PASSWORD = $KeyPassword
try {
    & $Apksigner sign --ks $StoreFile --ks-key-alias $KeyAlias --ks-pass env:BASHI_SIGN_STORE_PASSWORD --key-pass env:BASHI_SIGN_KEY_PASSWORD --out $Apk $Aligned
    Assert-BuildExit 'APK signing'
} finally {
    Remove-Item Env:BASHI_SIGN_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:BASHI_SIGN_KEY_PASSWORD -ErrorAction SilentlyContinue
}
& $Zipalign -c -p 4 $Apk
Assert-BuildExit 'Alignment verification'
& $Apksigner verify --verbose --print-certs $Apk
Assert-BuildExit 'Signature verification'
$Digest = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($Apk + '.sha256', $Digest + '  ' + [IO.Path]::GetFileName($Apk) + "`n", [Text.UTF8Encoding]::new($false))
Write-Output ("Built: $Apk")
Write-Output ("Application ID: $ApplicationId; signing: " + $(if ($ReleaseSigning) { 'provided release keystore' } else { 'local development key (not the official app signature)' }))
Write-Output ("SHA256: $Digest")
