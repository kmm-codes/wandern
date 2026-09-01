<#
.SYNOPSIS
Reserviert Wanderns AVD-Lane und führt die lokalen Android-Gerätetests aus.

.DESCRIPTION
Die Reservierung geschieht vor jedem Emulator- oder adb-Zugriff. Standardmäßig
gehört Pixel_Tablet_2 zu Wandern; der AVD war im PlayMorePiano-Pool ausdrücklich
ungepaart. Der Runner startet ihn headless, prüft fortschreitende Gast-Uptime,
führt connectedDebugAndroidTest aus und gibt Emulator plus Lease im finally frei.
#>
[CmdletBinding()]
param(
    [string]$Avd = 'Pixel_Tablet_2',
    [ValidateRange(30, 1200)][int]$BootTimeoutSeconds = 300,
    [ValidateRange(0, 7200)][int]$BudgetWaitSeconds = 900,
    [switch]$CaptureDebugScenes,
    [switch]$DebugScenesOnly,
    [switch]$KeepEmulator
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = Split-Path -Parent $PSScriptRoot

function Resolve-FleetModule {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_EMULATOR_FLEET_HOME)) {
        $candidates += Join-Path $env:ANDROID_EMULATOR_FLEET_HOME 'AndroidEmulatorFleet.psm1'
    }
    $candidates += Join-Path (Split-Path -Parent $repository) 'android-emulator-fleet\AndroidEmulatorFleet.psm1'
    $module = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($module)) {
        throw 'AndroidEmulatorFleet.psm1 fehlt. Setze ANDROID_EMULATOR_FLEET_HOME oder lege android-emulator-fleet neben das Projekt.'
    }
    return $module
}

function Resolve-RunningAvdSerial {
    param([Parameter(Mandatory)][string]$AdbPath, [Parameter(Mandatory)][string]$AvdName)
    $rows = @(& $AdbPath devices 2>&1)
    foreach ($row in $rows) {
        if ($row -notmatch '^(emulator-\d+)\s+(?:device|offline)') { continue }
        $candidate = $Matches[1]
        $name = @(& $AdbPath -s $candidate emu avd name 2>&1) | Select-Object -First 1
        if ($null -ne $name -and "$name".Trim() -eq $AvdName) { return $candidate }
    }
    return $null
}

function Wait-ForAvd {
    param([string]$AdbPath, [string]$AvdName, [int]$TimeoutSeconds)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $serial = Resolve-RunningAvdSerial -AdbPath $AdbPath -AvdName $AvdName
        if ($serial) {
            $boot = @(& $AdbPath -s $serial shell getprop sys.boot_completed 2>&1) | Select-Object -First 1
            if ($null -ne $boot -and "$boot".Trim() -eq '1') { return $serial }
        }
        Start-Sleep -Seconds 2
    }
    throw "EMULATOR-BOOT FAILED avd=${AvdName}: nicht innerhalb von ${TimeoutSeconds}s bereit."
}

function Assert-GuestUptimeAdvances {
    param([string]$AdbPath, [string]$Serial)
    $first = @(& $AdbPath -s $Serial shell cat /proc/uptime 2>&1) | Select-Object -First 1
    Start-Sleep -Seconds 2
    $second = @(& $AdbPath -s $Serial shell cat /proc/uptime 2>&1) | Select-Object -First 1
    $firstValue = [double]::Parse(("$first" -split '\s+')[0], [Globalization.CultureInfo]::InvariantCulture)
    $secondValue = [double]::Parse(("$second" -split '\s+')[0], [Globalization.CultureInfo]::InvariantCulture)
    if ($secondValue -le $firstValue) {
        throw "EMULATOR-LIVENESS FAILED serial=$Serial uptime1=$firstValue uptime2=$secondValue"
    }
    Write-Host "EMULATOR-LIVENESS OK serial=$Serial uptimeDelta=$([Math]::Round($secondValue - $firstValue, 2))s" -ForegroundColor Green
}

Import-Module (Resolve-FleetModule) -Force -DisableNameChecking
$lane = $null
$slot = $null
$serial = $null
$adb = $null
$shouldReleaseLane = -not $KeepEmulator

try {
    $lane = Acquire-AndroidEmulatorLane -Project 'Wandern' -AvdName $Avd `
        -Task 'local-android-gate' -AgentId "Wandern:$Avd" -ReattachRunningOwnedLane `
        -TimeoutSeconds $BudgetWaitSeconds

    $adb = (Get-Command adb -ErrorAction Stop).Source
    $serial = Resolve-RunningAvdSerial -AdbPath $adb -AvdName $Avd
    if (-not $serial) {
        $slot = Grant-AndroidEmulatorBudgetSlot -Project 'Wandern' -AvdName $Avd `
            -Task 'local-android-gate' -TimeoutSeconds $BudgetWaitSeconds
        $sdkCandidates = @(
            $env:ANDROID_SDK_ROOT,
            $env:ANDROID_HOME,
            (Split-Path -Parent (Split-Path -Parent $adb))
        ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
        $emulator = $sdkCandidates |
            ForEach-Object { Join-Path $_ 'emulator\emulator.exe' } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if ([string]::IsNullOrWhiteSpace($emulator)) {
            throw "emulator.exe fehlt in den SDK-Kandidaten: $($sdkCandidates -join ', ')"
        }
        Start-Process -FilePath $emulator -WindowStyle Hidden -ArgumentList @(
            '-avd', $Avd, '-no-window', '-no-audio', '-no-boot-anim',
            '-no-snapshot-load', '-no-snapshot-save', '-gpu', 'host'
        ) | Out-Null
        $serial = Wait-ForAvd -AdbPath $adb -AvdName $Avd -TimeoutSeconds $BootTimeoutSeconds
        Revoke-AndroidEmulatorBudgetSlot -Lease $slot
        $slot = $null
    }

    Assert-GuestUptimeAdvances -AdbPath $adb -Serial $serial

    # Die dedizierte CI-Lane muss unabhängig von vorherigen Läufen starten.
    # Das gilt nur für den Emulator; run.ps1 und physische Geräte behalten ihre Daten.
    $installedPackages = @(& $adb -s $serial shell pm list packages de.wandern.app 2>&1)
    if (($installedPackages -join "`n") -match 'package:de\.wandern\.app(?:\s|$)') {
        $clearOutput = @(& $adb -s $serial shell pm clear de.wandern.app 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($clearOutput -join "`n") -notmatch 'Success') {
            throw "EMULATOR-STATE RESET FAILED serial=${serial}: $($clearOutput -join ' ')"
        }
    }
    Write-Host "EMULATOR-STATE RESET OK serial=$serial package=de.wandern.app" -ForegroundColor Green

    if ($DebugScenesOnly) {
        & (Join-Path $repository 'gradlew.bat') :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Debug-Build für Szenen fehlgeschlagen.' }
        & $adb -s $serial install -r (Join-Path $repository 'app\build\outputs\apk\debug\app-debug.apk')
        if ($LASTEXITCODE -ne 0) { throw 'Debug-APK konnte nicht installiert werden.' }
    } else {
        & (Join-Path $repository 'scripts\check.ps1') -DeviceSerial $serial
        if ($LASTEXITCODE -ne 0) { throw 'Der lokale Android-Gate ist fehlgeschlagen.' }
    }
    if ($CaptureDebugScenes -or $DebugScenesOnly) {
        $debugScenes = @('route-collapsed', 'route-medium', 'route-stats-medium', 'route-expanded')
        for ($sceneIndex = 0; $sceneIndex -lt $debugScenes.Count; $sceneIndex++) {
            $scene = $debugScenes[$sceneIndex]
            & (Join-Path $repository 'scripts\debug-scene.ps1') `
                -Scene $scene -DeviceSerial $serial -WaitMilliseconds 1200 `
                -DismissSystemEducation:($DebugScenesOnly -and $sceneIndex -eq 0)
            if ($LASTEXITCODE -ne 0) { throw "Debug-Screenshot fehlgeschlagen: $scene" }
        }
    }
    Write-Host "WANDERN-E2E OK avd=$Avd serial=$serial" -ForegroundColor Green
}
finally {
    if ($null -ne $slot) { Revoke-AndroidEmulatorBudgetSlot -Lease $slot }
    if (-not $KeepEmulator -and -not [string]::IsNullOrWhiteSpace($serial) -and
        -not [string]::IsNullOrWhiteSpace($adb)) {
        & $adb -s $serial emu kill 2>&1 | Out-Null
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
        while ((Test-AndroidFleetAvdRunning -AvdName $Avd) -and [DateTimeOffset]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 500
        }
    }
    if ($shouldReleaseLane -and $null -ne $lane) {
        Release-AndroidEmulatorLane -Lease $lane
    }
}
