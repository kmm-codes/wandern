param(
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & .\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Lokale JVM-/Lint-/Build-Prüfung fehlgeschlagen."
    }

    if ($DeviceSerial) {
        & .\gradlew.bat --no-daemon :app:connectedDebugAndroidTest "-Pandroid.injected.device.serial=$DeviceSerial"
        if ($LASTEXITCODE -ne 0) {
            throw "Instrumentierte Tests auf Gerät $DeviceSerial fehlgeschlagen."
        }
    }
} finally {
    Pop-Location
}
