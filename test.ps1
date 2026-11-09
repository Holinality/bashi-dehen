[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tools/build-environment.ps1')
$Classes = Join-Path $BuildRoot 'classes'
if (-not (Test-Path -LiteralPath $Classes -PathType Container)) { throw 'Run build.ps1 first; production classes are required.' }
$TestClasses = Join-Path $BuildRoot 'test-classes'
Clear-GeneratedDirectory $TestClasses
$Classpath = $Classes + [IO.Path]::PathSeparator + $AndroidJar
$Sources = @(Get-ChildItem (Join-Path $ProjectRoot 'tests') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $Javac -encoding UTF-8 -classpath $Classpath -d $TestClasses $Sources
Assert-BuildExit 'Test compilation'
$Tests = @('BusSearchSelfTest', 'TransitRulesSelfTest', 'DirectionArrivalsSelfTest', 'ReadableColorsSelfTest', 'GuangdongFilterSelfTest', 'MetroTimesSelfTest', 'MetroLineResolverSelfTest', 'LocationQualitySelfTest', 'TransitExperienceSelfTest', 'RouteProgressSelectionSelfTest', 'DesignTokensSelfTest', 'TransitModeSelfTest', 'MetroPatternsSelfTest', 'TemporaryMetroCacheSelfTest', 'MetroCatalogSelfTest', 'MetroScheduleSelfTest', 'SettingsRulesSelfTest', 'BusRouteMatchSelfTest', 'RegionalTransitSelfTest', 'MapGeometrySelfTest', 'RailStopTimesSelfTest', 'DiagramPathSelfTest', 'MetroRoutingSelfTest', 'NetworkLimitsSelfTest', 'StationLabelPlacementSelfTest', 'PrivacyRulesSelfTest')
Push-Location $ProjectRoot
try {
    foreach ($Test in $Tests) {
        & $Java -classpath ($TestClasses + [IO.Path]::PathSeparator + $Classpath) ('com.holi.prdbus.' + $Test)
        Assert-BuildExit ('Regression ' + $Test)
    }
} finally { Pop-Location }
Write-Output ('PASS: ' + $Tests.Count + ' regression test programs.')
