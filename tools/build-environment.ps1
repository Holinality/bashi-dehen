$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalConfig = @{}
$LocalConfigFile = Join-Path $ProjectRoot 'local.properties'
if (Test-Path -LiteralPath $LocalConfigFile) {
    foreach ($Line in [IO.File]::ReadAllLines($LocalConfigFile)) {
        if ($Line -match '^\s*([^#!\s][^=]*)=(.*)$') {
            $LocalConfig[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
}
function Get-BuildSetting([string]$Name, [string]$Default = '') {
    $Value = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value }
    if ($LocalConfig.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace($LocalConfig[$Name])) { return $LocalConfig[$Name] }
    return $Default
}
function Resolve-ProjectPath([string]$Value) {
    if ([IO.Path]::IsPathRooted($Value)) { return [IO.Path]::GetFullPath($Value) }
    return [IO.Path]::GetFullPath((Join-Path $ProjectRoot $Value))
}
$WindowsHost = $env:OS -eq 'Windows_NT'
$ExeSuffix = if ($WindowsHost) { '.exe' } else { '' }
$ScriptSuffix = if ($WindowsHost) { '.bat' } else { '' }
$JdkHome = Get-BuildSetting 'JAVA_HOME'
if (-not $JdkHome) {
    $JavacCommand = Get-Command ('javac' + $ExeSuffix) -ErrorAction SilentlyContinue
    if (-not $JavacCommand) { throw 'Install JDK 17 or later and set JAVA_HOME.' }
    $JdkHome = Split-Path -Parent (Split-Path -Parent $JavacCommand.Source)
}
$JdkHome = Resolve-ProjectPath $JdkHome
$SdkHome = Get-BuildSetting 'ANDROID_SDK_ROOT' (Get-BuildSetting 'ANDROID_HOME' (Get-BuildSetting 'sdk.dir'))
if (-not $SdkHome) { throw 'Set ANDROID_SDK_ROOT or ANDROID_HOME, or sdk.dir in local.properties.' }
$SdkHome = Resolve-ProjectPath $SdkHome
$BuildToolsVersion = Get-BuildSetting 'ANDROID_BUILD_TOOLS' '35.0.0'
$CompileSdk = Get-BuildSetting 'ANDROID_COMPILE_SDK' '35'
$AndroidJar = Join-Path $SdkHome "platforms/android-$CompileSdk/android.jar"
$BuildTools = Join-Path $SdkHome "build-tools/$BuildToolsVersion"
$Javac = Join-Path $JdkHome ('bin/javac' + $ExeSuffix)
$Java = Join-Path $JdkHome ('bin/java' + $ExeSuffix)
$Jar = Join-Path $JdkHome ('bin/jar' + $ExeSuffix)
$Keytool = Join-Path $JdkHome ('bin/keytool' + $ExeSuffix)
$Aapt2 = Join-Path $BuildTools ('aapt2' + $ExeSuffix)
$D8 = Join-Path $BuildTools ('d8' + $ScriptSuffix)
$Zipalign = Join-Path $BuildTools ('zipalign' + $ExeSuffix)
$Apksigner = Join-Path $BuildTools ('apksigner' + $ScriptSuffix)
foreach ($Required in @($AndroidJar, $Javac, $Java, $Jar, $Keytool, $Aapt2, $D8, $Zipalign, $Apksigner)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "Missing build dependency: $Required" }
}
$env:JAVA_HOME = $JdkHome
$env:Path = (Join-Path $JdkHome 'bin') + [IO.Path]::PathSeparator + $env:Path
$BuildRoot = Join-Path $ProjectRoot 'build'
if ((Test-Path -LiteralPath $BuildRoot) -and ((Get-Item -LiteralPath $BuildRoot).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'The build directory must not be a symbolic link or junction.'
}
function Clear-GeneratedDirectory([string]$Path) {
    $AbsolutePath = [IO.Path]::GetFullPath($Path)
    $Prefix = [IO.Path]::GetFullPath($BuildRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $Comparison = if ($WindowsHost) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if (-not $AbsolutePath.StartsWith($Prefix, $Comparison)) { throw 'Generated output escaped the build directory.' }
    if (Test-Path -LiteralPath $AbsolutePath) {
        if ((Get-Item -LiteralPath $AbsolutePath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Generated output must not be a symbolic link or junction.' }
        Remove-Item -LiteralPath $AbsolutePath -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $AbsolutePath | Out-Null
}
function Assert-BuildExit([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}
