<#
.SYNOPSIS
Builds, updates and starts Wandern on an Android device.

.DESCRIPTION
With one connected device, it is selected automatically. With several ready
devices, an interactive terminal shows a numbered picker. Automated calls must
select a device explicitly with -Device.

The app is always installed with adb install -r. The script never uninstalls
the package and never clears application data.

.EXAMPLE
.\run.ps1

.EXAMPLE
.\run.ps1 -Device 5HFEH6AEWSSCNF79

.EXAMPLE
.\run.ps1 -Device emulator-5556 -NoBuild
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Alias("d")]
    [string]$Device,

    [switch]$NoBuild,

    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$applicationId = "de.wandern.app"
$launcherComponent = "$applicationId/.ui.MainActivity"
$apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

function Show-Usage {
    @"
Wandern Android launcher

Usage:
  .\run.ps1 [-Device <adb-serial>] [-NoBuild] [-WhatIf]

Examples:
  .\run.ps1
  .\run.ps1 -Device 5HFEH6AEWSSCNF79
  .\run.ps1 -Device emulator-5556 -NoBuild

Behavior:
  - builds the current Debug APK unless -NoBuild is used
  - updates the selected installation with adb install -r
  - preserves application data and the existing debug signature
  - starts Wandern and verifies the installed APK hash
"@
}

function Write-Step {
    param([string]$Message)
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Test-InteractiveConsole {
    if (-not [Environment]::UserInteractive) { return $false }
    if ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected) { return $false }
    try {
        return $Host.Name -eq "ConsoleHost" -and $null -ne $Host.UI.RawUI
    }
    catch {
        return $false
    }
}

function Get-ReadyDevices {
    param([string]$AdbPath)

    $output = @(& $AdbPath devices -l 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "ADB-Geräteliste konnte nicht gelesen werden: $($output -join ' ')"
    }

    $devices = foreach ($line in $output) {
        $match = [regex]::Match("$line".Trim(), '^(\S+)\s+device(?:\s+(.*))?$')
        if (-not $match.Success) { continue }
        $serial = $match.Groups[1].Value
        $details = $match.Groups[2].Value
        $modelMatch = [regex]::Match($details, '(?:^|\s)model:([^\s]+)')
        $model = if ($modelMatch.Success) { $modelMatch.Groups[1].Value.Replace('_', ' ') } else { "Android-Gerät" }
        [pscustomobject]@{
            Serial = $serial
            Model = $model
            Details = $details
        }
    }

    return @($devices)
}

function Select-Device {
    param(
        [object[]]$ReadyDevices,
        [string]$RequestedDevice
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedDevice)) {
        $matches = @($ReadyDevices | Where-Object { $_.Serial -eq $RequestedDevice })
        if ($matches.Count -ne 1) {
            $available = if ($ReadyDevices.Count -gt 0) {
                $ReadyDevices.Serial -join ", "
            } else {
                "keine"
            }
            throw "Gerät '$RequestedDevice' ist nicht bereit. Bereit: $available"
        }
        return $matches[0]
    }

    if ($ReadyDevices.Count -eq 0) {
        throw "Kein bereites Android-Gerät gefunden. Prüfe 'adb devices'."
    }
    if ($ReadyDevices.Count -eq 1) {
        return $ReadyDevices[0]
    }
    if (-not (Test-InteractiveConsole)) {
        $commands = $ReadyDevices | ForEach-Object {
            "  .\run.ps1 -Device $($_.Serial)  # $($_.Model)"
        }
        throw "Mehrere Geräte sind bereit. Wähle eines explizit:`n$($commands -join [Environment]::NewLine)"
    }

    Write-Host ""
    Write-Host "Mehrere Android-Geräte sind bereit:" -ForegroundColor Yellow
    for ($index = 0; $index -lt $ReadyDevices.Count; $index++) {
        Write-Host "  [$($index + 1)] $($ReadyDevices[$index].Model) ($($ReadyDevices[$index].Serial))"
    }
    Write-Host "  [q] Abbrechen"

    while ($true) {
        $answer = (Read-Host "Gerät auswählen [1-$($ReadyDevices.Count)]").Trim()
        if ($answer -match '^(q|quit|c|cancel|0)$') {
            throw "Geräteauswahl abgebrochen."
        }
        $selection = 0
        if (
            [int]::TryParse($answer, [ref]$selection) -and
            $selection -ge 1 -and
            $selection -le $ReadyDevices.Count
        ) {
            return $ReadyDevices[$selection - 1]
        }
        Write-Host "Bitte eine Zahl zwischen 1 und $($ReadyDevices.Count) eingeben." -ForegroundColor Yellow
    }
}

function Assert-ArtifactFreshness {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [string]$LocalApk
    )

    $packageOutput = @(& $AdbPath -s $Serial shell pm path $applicationId 2>&1)
    $remotePath = $packageOutput |
        ForEach-Object { "$_".Trim() } |
        Where-Object { $_ -like "package:*base.apk" } |
        ForEach-Object { $_.Substring("package:".Length) } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($remotePath)) {
        throw "Installiertes Basispaket konnte nicht gefunden werden: $($packageOutput -join ' ')"
    }

    $remoteHashOutput = @(& $AdbPath -s $Serial shell sha256sum $remotePath 2>&1)
    if ($LASTEXITCODE -ne 0 -or $remoteHashOutput.Count -eq 0) {
        throw "Installierte APK konnte nicht gehasht werden: $($remoteHashOutput -join ' ')"
    }
    $remoteHash = ("$($remoteHashOutput[0])" -split '\s+')[0].ToLowerInvariant()
    $localHash = (Get-FileHash -LiteralPath $LocalApk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($remoteHash -ne $localHash) {
        throw "ARTIFACT-FRESHNESS FAILED: Gerät=$remoteHash, Build=$localHash"
    }

    Write-Host "ARTIFACT-FRESHNESS OK device=$Serial sha256=$localHash" -ForegroundColor Green
}

if ($Help) {
    Show-Usage
    exit 0
}

try {
    $adb = (Get-Command adb -ErrorAction Stop).Source
    $selected = Select-Device -ReadyDevices (Get-ReadyDevices -AdbPath $adb) -RequestedDevice $Device
    Write-Host "Gerät: $($selected.Model) ($($selected.Serial))" -ForegroundColor DarkGray

    if (-not $NoBuild -and $PSCmdlet.ShouldProcess($apkPath, "Debug-APK bauen")) {
        Write-Step "Debug-APK bauen"
        & (Join-Path $PSScriptRoot "gradlew.bat") :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Android-Build fehlgeschlagen." }
    }

    if (-not $WhatIfPreference -and -not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        throw "APK fehlt: $apkPath. Ohne -NoBuild erneut ausführen."
    }

    if ($PSCmdlet.ShouldProcess($selected.Serial, "Wandern per adb install -r aktualisieren")) {
        Write-Step "App aktualisieren (Daten bleiben erhalten)"
        $installOutput = @(& $adb -s $selected.Serial install -r $apkPath 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "Installation fehlgeschlagen. Die App wurde nicht deinstalliert: $($installOutput -join ' ')"
        }
        $installOutput | ForEach-Object { Write-Host $_ }
    }

    if ($PSCmdlet.ShouldProcess($selected.Serial, "Wandern starten")) {
        Write-Step "App starten"
        $startOutput = @(& $adb -s $selected.Serial shell am start -W -n $launcherComponent 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "App-Start fehlgeschlagen: $($startOutput -join ' ')"
        }
        Assert-ArtifactFreshness -AdbPath $adb -Serial $selected.Serial -LocalApk $apkPath
        $pid = (& $adb -s $selected.Serial shell pidof $applicationId 2>$null | Select-Object -First 1)
        Write-Host ""
        Write-Host "Wandern läuft auf $($selected.Serial)." -ForegroundColor Green
        if (-not [string]::IsNullOrWhiteSpace($pid)) {
            Write-Host "Logs: adb -s $($selected.Serial) logcat --pid=$pid"
        }
    }

    exit 0
}
catch {
    Write-Host ""
    Write-Host "Wandern-Start fehlgeschlagen: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
