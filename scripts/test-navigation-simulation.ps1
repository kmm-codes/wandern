<#
.SYNOPSIS
Führt einen vollständigen semantischen Navigations-Smoke-Test auf einem Emulator aus.

.DESCRIPTION
Prüft ohne UI-Klicks: Aufzeichnungsstart, Folgen der Route, Off-Route-Erkennung,
Wiedereinstieg, Pause und Fortsetzen. Screenshots und strukturierte Statusdateien
landen im angegebenen Ausgabeordner.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$OutputDirectory = '.\.codex-device-captures\navigation-simulation',
    [switch]$Install,
    [switch]$LiveRoute,
    [string]$Start = 'Sandweier, Baden-Baden',
    [string]$Destination = 'Iffezheim'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$simulator = Join-Path $PSScriptRoot 'nav-sim.ps1'
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null

function Invoke-Simulation {
    param(
        [Parameter(Mandatory)]
        [string]$Command,
        [hashtable]$Parameters = @{}
    )
    $invokeParameters = @{}
    if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
        $invokeParameters.DeviceSerial = $DeviceSerial
    }
    foreach ($entry in $Parameters.GetEnumerator()) {
        $invokeParameters[$entry.Key] = $entry.Value
    }
    $json = & $simulator $Command @invokeParameters | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Simulationsschritt '$Command' ist fehlgeschlagen." }
    return $json | ConvertFrom-Json
}

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Navigations-Smoke-Test: $Message" }
}

function Test-AtDestination {
    param([Parameter(Mandatory)]$Status)
    if ($Status.navigationManeuver -ne 'ARRIVE') { return $false }
    if ($null -eq $Status.navigationDistanceMeters) { return $false }
    return [double]$Status.navigationDistanceMeters -le 18
}

$prepared = $false
try {
    $prepareParameters = @{ Install = $Install }
    if ($LiveRoute) {
        $prepareParameters.Start = $Start
        $prepareParameters.Destination = $Destination
        $prepare = Invoke-Simulation -Command plan -Parameters $prepareParameters
    } else {
        $prepare = Invoke-Simulation -Command fixture -Parameters $prepareParameters
    }
    $prepared = $true
    Assert-Condition ($prepare.recordingState -eq 'recording') 'Aufzeichnung wurde nicht gestartet.'

    $follow = Invoke-Simulation -Command follow -Parameters @{
        DistanceMeters = 1000
        SpeedKmh = 5
        Screenshot = (Join-Path $output '01-follow.png')
    }
    Assert-Condition ($follow.pointCount -ge 90) 'Zu wenige Standortpunkte wurden verarbeitet.'
    Assert-Condition ($follow.distanceMeters -ge 900 -and $follow.distanceMeters -le 1100) `
        "Erwartete etwa 1 km, erhalten: $($follow.distanceMeters) m."
    Assert-Condition (-not (Test-AtDestination $follow)) `
        'Die Simulation steht schon am Ziel; der Ankunftsdialog würde die weiteren Schritte verdecken.'

    $deviated = Invoke-Simulation -Command deviate -Parameters @{
        Direction = 'right'
        DistanceMeters = 300
        SpeedKmh = 5
        Screenshot = (Join-Path $output '02-off-route.png')
    }
    Assert-Condition ([bool]$deviated.confirmedOffRoute) 'Off-Route-Zustand wurde nicht bestätigt.'
    Assert-Condition ($deviated.routeDeviationMeters -ge 50) `
        "Abweichung war unerwartet klein: $($deviated.routeDeviationMeters) m."

    $rejoined = Invoke-Simulation -Command rejoin -Parameters @{
        SpeedKmh = 5
        Screenshot = (Join-Path $output '03-rejoined.png')
    }
    Assert-Condition (-not (Test-AtDestination $rejoined)) `
        'Der Wiedereinstieg endete am Ziel; der Ankunftsdialog würde die Screenshots verdecken.'
    Assert-Condition (-not [bool]$rejoined.confirmedOffRoute) 'Off-Route-Zustand blieb nach Wiedereinstieg aktiv.'
    Assert-Condition ($rejoined.routeDeviationMeters -le 2) `
        "Wiedereinstieg endete nicht auf der Route: $($rejoined.routeDeviationMeters) m."

    $paused = Invoke-Simulation -Command pause
    Assert-Condition ($paused.recordingState -eq 'paused') 'Pause wurde nicht übernommen.'
    $resumed = Invoke-Simulation -Command resume
    Assert-Condition ($resumed.recordingState -eq 'recording') 'Fortsetzen wurde nicht übernommen.'

    [pscustomobject]@{
        result = 'passed'
        routeReference = $prepare.routeReference
        followedDistanceMeters = [Math]::Round([double]$follow.distanceMeters, 1)
        maximumVerifiedDeviationMeters = [Math]::Round([double]$deviated.routeDeviationMeters, 1)
        rejoinDeviationMeters = [Math]::Round([double]$rejoined.routeDeviationMeters, 2)
        screenshots = @(
            (Join-Path $output '01-follow.png')
            (Join-Path $output '02-off-route.png')
            (Join-Path $output '03-rejoined.png')
        )
    } | ConvertTo-Json -Depth 4
} finally {
    if ($prepared) {
        try { Invoke-Simulation -Command discard | Out-Null } catch { Write-Warning $_ }
    }
}
