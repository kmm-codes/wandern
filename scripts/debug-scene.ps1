[CmdletBinding()]
param(
    [ValidateSet(
        "route-collapsed",
        "route-expanded",
        "route-elevation-expanded",
        "route-paused-expanded",
        "route-off-route-expanded",
        "route-detour-expanded",
        "route-navigation-collapsed",
        "route-navigation-expanded",
        "free-expanded"
    )]
    [string]$Scene = "route-expanded",
    [string]$DeviceSerial,
    [string]$OutputPath,
    [int]$WaitMilliseconds = 1800,
    [switch]$DismissSystemEducation,
    [switch]$NoScreenshot
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$deviceArgs = if ([string]::IsNullOrWhiteSpace($DeviceSerial)) { @() } else { @("-s", $DeviceSerial) }

& $adb @deviceArgs shell am start -W `
    -n "de.wandern.app/.ui.MainActivity" `
    -a "de.wandern.app.DEBUG_SCENARIO" `
    --es scenario $Scene
if ($LASTEXITCODE -ne 0) { throw "Debug-Szene '$Scene' konnte nicht geöffnet werden." }

if ($DismissSystemEducation) {
    Start-Sleep -Milliseconds 700
    $sizeLine = @(& $adb @deviceArgs shell wm size) | Select-Object -Last 1
    if ("$sizeLine" -match '(\d+)x(\d+)') {
        $dismissX = [int]([int]$Matches[1] * 0.5)
        $dismissY = [int]([int]$Matches[2] * 0.68)
        & $adb @deviceArgs shell input tap $dismissX $dismissY | Out-Null
    }
}
Start-Sleep -Milliseconds $WaitMilliseconds
if ($NoScreenshot) { return }

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $captureDirectory = Join-Path $PSScriptRoot "..\.codex-device-captures\debug-scenes"
    $OutputPath = Join-Path $captureDirectory "$Scene.png"
}
$absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $absoluteOutput
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$remotePath = "/sdcard/Download/wandern-$Scene.png"
& $adb @deviceArgs shell screencap -p $remotePath
if ($LASTEXITCODE -ne 0) { throw "Screenshot konnte auf dem Gerät nicht erzeugt werden." }
try {
    & $adb @deviceArgs pull $remotePath $absoluteOutput | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Screenshot konnte nicht kopiert werden." }
} finally {
    & $adb @deviceArgs shell rm $remotePath | Out-Null
}
Write-Host "Debug-Szene '$Scene': $absoluteOutput"
