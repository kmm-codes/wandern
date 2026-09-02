[CmdletBinding(DefaultParameterSetName = 'Single')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Single')]
    [double]$Latitude,
    [Parameter(Mandatory, ParameterSetName = 'Single')]
    [double]$Longitude,
    [Parameter(ParameterSetName = 'Single')]
    [double]$Altitude = 0,
    [Parameter(Mandatory, ParameterSetName = 'Sequence')]
    [ValidateNotNullOrEmpty()]
    [string[]]$Point,
    [string]$DeviceSerial,
    [ValidateRange(0, 60000)]
    [int]$IntervalMilliseconds = 1000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb -ErrorAction Stop).Source

if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $emulators = @(& $adb devices) |
        Where-Object { $_ -match '^(emulator-\d+)\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
    if ($emulators.Count -ne 1) {
        throw "Genau ein laufender Emulator ist erforderlich oder -DeviceSerial muss gesetzt werden. Gefunden: $($emulators -join ', ')"
    }
    $DeviceSerial = $emulators[0]
}
if ($DeviceSerial -notmatch '^emulator-\d+$') {
    throw "mock-gps.ps1 unterstützt nur Android-Emulatoren, erhalten: $DeviceSerial"
}

$culture = [Globalization.CultureInfo]::InvariantCulture
function Send-MockPosition {
    param([double]$Lat, [double]$Lon, [double]$Alt)
    $arguments = @(
        '-s', $DeviceSerial, 'emu', 'geo', 'fix',
        $Lon.ToString('R', $culture),
        $Lat.ToString('R', $culture),
        $Alt.ToString('R', $culture)
    )
    $output = @(& $adb @arguments 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'OK') {
        throw "Mock-Position konnte nicht gesetzt werden: $($output -join ' ')"
    }
    Write-Host ("GPS {0}, {1} ({2} m) -> {3}" -f $Lat, $Lon, $Alt, $DeviceSerial)
}

$positions = if ($PSCmdlet.ParameterSetName -eq 'Single') {
    @([pscustomobject]@{ Latitude = $Latitude; Longitude = $Longitude; Altitude = $Altitude })
} else {
    @($Point | ForEach-Object {
        $parts = $_ -split ',' | ForEach-Object { $_.Trim() }
        if ($parts.Count -lt 2 -or $parts.Count -gt 3) {
            throw "Ungültiger Punkt '$_'. Erwartet: latitude,longitude[,altitude]"
        }
        [pscustomobject]@{
            Latitude = [double]::Parse($parts[0], $culture)
            Longitude = [double]::Parse($parts[1], $culture)
            Altitude = if ($parts.Count -eq 3) { [double]::Parse($parts[2], $culture) } else { 0.0 }
        }
    })
}

for ($index = 0; $index -lt $positions.Count; $index++) {
    $position = $positions[$index]
    Send-MockPosition -Lat $position.Latitude -Lon $position.Longitude -Alt $position.Altitude
    if ($index -lt $positions.Count - 1 -and $IntervalMilliseconds -gt 0) {
        Start-Sleep -Milliseconds $IntervalMilliseconds
    }
}
