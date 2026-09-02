<#
.SYNOPSIS
Steuert semantische Navigationssimulationen in der Debug-App ohne UI-Klicks oder Punktlisten.

.EXAMPLE
.\scripts\nav-sim.ps1 plan -Start "Sandweier, Baden-Baden" -Destination "Iffezheim"
.\scripts\nav-sim.ps1 follow -DistanceMeters 1000 -SpeedKmh 5 -Screenshot .\captures\follow.png
.\scripts\nav-sim.ps1 deviate -Direction right -DistanceMeters 500 -SpeedKmh 5
.\scripts\nav-sim.ps1 open-rejoin -Screenshot .\captures\rejoin-options.png
.\scripts\nav-sim.ps1 rejoin -SpeedKmh 5 -Screenshot .\captures\rejoined.png
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('plan', 'fixture', 'follow', 'deviate', 'rejoin', 'open-rejoin', 'pause', 'resume', 'finish', 'discard', 'status')]
    [string]$Command = 'status',
    [string]$Start,
    [Alias('Ziel')]
    [string]$Destination,
    [ValidateSet('HIKING', 'RUNNING', 'CYCLING', 'E_BIKE')]
    [string]$ActivityType = 'HIKING',
    [ValidateRange(1, 100000)]
    [double]$DistanceMeters = 1000,
    [ValidateRange(0.1, 100)]
    [double]$SpeedKmh = 5,
    [ValidateRange(1, 100)]
    [double]$StepMeters = 10,
    [ValidateSet('left', 'right')]
    [string]$Direction = 'right',
    [string]$DeviceSerial,
    [string]$Screenshot,
    [ValidateRange(1, 180)]
    [int]$TimeoutSeconds = 90,
    [switch]$Install
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$adb = (Get-Command adb -ErrorAction Stop).Source

if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $devices = @(& $adb devices) |
        Where-Object { $_ -match '^(emulator-\d+|[^\s]+)\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -ne 1) {
        throw "Genau ein ADB-Gerät ist erforderlich oder -DeviceSerial muss gesetzt werden. Gefunden: $($devices -join ', ')"
    }
    $DeviceSerial = $devices[0]
}
$deviceArgs = @('-s', $DeviceSerial)

if ($Install) {
    & (Join-Path $repository 'gradlew.bat') :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Debug-Build fehlgeschlagen.' }
    & $adb @deviceArgs install -r (Join-Path $repository 'app\build\outputs\apk\debug\app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Debug-APK konnte nicht installiert werden.' }
}

if ($Command -in @('plan', 'fixture')) {
    if ($Command -eq 'plan' -and
        ([string]::IsNullOrWhiteSpace($Start) -or [string]::IsNullOrWhiteSpace($Destination))) {
        throw 'plan benötigt -Start und -Destination.'
    }
    # A location foreground service still requires the Android runtime permissions in simulation mode.
    & $adb @deviceArgs shell pm grant de.wandern.app android.permission.ACCESS_FINE_LOCATION 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Standortberechtigung konnte nicht gesetzt werden.' }
    & $adb @deviceArgs shell pm grant de.wandern.app android.permission.ACCESS_COARSE_LOCATION 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Standortberechtigung konnte nicht gesetzt werden.' }
    & $adb @deviceArgs shell pm grant de.wandern.app android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null
}

$requestId = [guid]::NewGuid().ToString('N')
$arguments = @($deviceArgs) + @(
    'shell', 'am', 'broadcast', '-W',
    '-a', 'de.wandern.app.DEBUG_NAVIGATION',
    '-p', 'de.wandern.app',
    '-f', '0x20',
    '--es', 'command', $Command,
    '--es', 'request_id', $requestId
)
if ($Command -eq 'plan') {
    $startBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Start))
    $destinationBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Destination))
    $arguments += @(
        '--es', 'start_query_base64', $startBase64,
        '--es', 'destination_query_base64', $destinationBase64,
        '--es', 'activity_type', $ActivityType
    )
}
if ($Command -in @('follow', 'deviate')) {
    $arguments += @('--es', 'distance_meters', $DistanceMeters.ToString('R', [Globalization.CultureInfo]::InvariantCulture))
}
if ($Command -in @('follow', 'deviate', 'rejoin')) {
    $arguments += @(
        '--es', 'speed_kmh', $SpeedKmh.ToString('R', [Globalization.CultureInfo]::InvariantCulture),
        '--es', 'step_meters', $StepMeters.ToString('R', [Globalization.CultureInfo]::InvariantCulture)
    )
}
if ($Command -eq 'deviate') {
    $arguments += @('--es', 'direction', $Direction)
}

$broadcastOutput = @(& $adb @arguments 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Debug-Befehl konnte nicht gesendet werden: $($broadcastOutput -join ' ')"
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$status = $null
while ([DateTimeOffset]::UtcNow -lt $deadline) {
    $rawStatus = @(& $adb @deviceArgs exec-out run-as de.wandern.app cat files/debug-navigation/status.json 2>$null)
    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($rawStatus -join "`n"))) {
        try {
            $candidate = ($rawStatus -join "`n") | ConvertFrom-Json
            if ($candidate.requestId -eq $requestId -and $candidate.phase -ne 'running') {
                $status = $candidate
                break
            }
        } catch {
            # The receiver replaces the file atomically enough for humans, but adb may catch it mid-write.
        }
    }
    Start-Sleep -Milliseconds 100
}
if ($null -eq $status) {
    throw "Debug-Befehl '$Command' lieferte innerhalb von ${TimeoutSeconds}s keinen Abschlussstatus."
}
if ($status.phase -eq 'error') {
    throw "Debug-Befehl '$Command' fehlgeschlagen: $($status.message)"
}

if ($Command -in @('plan', 'fixture')) {
    # Android deliberately blocks a background BroadcastReceiver from launching a location FGS.
    # `am start` is the privileged ADB boundary; MainActivity then starts the real service while foreground.
    $startOutput = @(& $adb @deviceArgs shell am start -W -n de.wandern.app/.ui.MainActivity `
        --es de.wandern.app.MAIN_TOUR_REFERENCE ([string]$status.routeReference) `
        --ez de.wandern.app.DEBUG_START_RECORDING true `
        --es de.wandern.app.DEBUG_ACTIVITY_TYPE ([string]$status.activityType) 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Simulationsaufzeichnung konnte nicht geöffnet werden: $($startOutput -join ' ')"
    }

    $recordingDeadline = [DateTimeOffset]::UtcNow.AddSeconds([Math]::Min($TimeoutSeconds, 30))
    do {
        Start-Sleep -Milliseconds 150
        $statusRequestId = [guid]::NewGuid().ToString('N')
        & $adb @deviceArgs shell am broadcast -W -a de.wandern.app.DEBUG_NAVIGATION -p de.wandern.app -f 0x20 `
            --es command status --es request_id $statusRequestId 2>&1 | Out-Null
        $rawRecordingStatus = @(& $adb @deviceArgs exec-out run-as de.wandern.app cat files/debug-navigation/status.json 2>$null)
        if ($LASTEXITCODE -eq 0) {
            try {
                $candidate = ($rawRecordingStatus -join "`n") | ConvertFrom-Json
                if ($candidate.requestId -eq $statusRequestId -and $candidate.phase -eq 'ok') {
                    $status = $candidate
                }
            } catch {
                # Retry while the receiver is replacing its status file.
            }
        }
    } while ($status.recordingState -ne 'recording' -and [DateTimeOffset]::UtcNow -lt $recordingDeadline)

    if ($status.recordingState -ne 'recording') {
        throw 'Die vorbereitete Route wurde geöffnet, aber die Simulationsaufzeichnung startete nicht.'
    }
}

if (-not [string]::IsNullOrWhiteSpace($Screenshot)) {
    Start-Sleep -Milliseconds 1200
    $absoluteScreenshot = [System.IO.Path]::GetFullPath($Screenshot)
    $directory = Split-Path -Parent $absoluteScreenshot
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $remotePath = "/sdcard/Download/wandern-nav-sim-$requestId.png"
    & $adb @deviceArgs shell screencap -p $remotePath
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot konnte nicht erzeugt werden.' }
    try {
        & $adb @deviceArgs pull $remotePath $absoluteScreenshot | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Screenshot konnte nicht kopiert werden.' }
    } finally {
        & $adb @deviceArgs shell rm $remotePath | Out-Null
    }
    $status | Add-Member -NotePropertyName screenshot -NotePropertyValue $absoluteScreenshot
}

$status | ConvertTo-Json -Depth 8
