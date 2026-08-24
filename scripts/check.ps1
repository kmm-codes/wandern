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
        $adb = (Get-Command adb -ErrorAction Stop).Source
        $deviceState = (& $adb -s $DeviceSerial get-state 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $deviceState -ne "device") {
            throw "ADB-Gerät $DeviceSerial ist nicht bereit: $deviceState"
        }

        & .\gradlew.bat --no-daemon :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) {
            throw "Test-APK konnte nicht gebaut werden."
        }

        # Updates bewusst ohne vorheriges Deinstallieren: So bleiben Debug-Signatur
        # und Xiaomi-Freigabe stabil und es gibt keinen Dialog bei jedem Testlauf.
        & $adb -s $DeviceSerial install -r .\app\build\outputs\apk\debug\app-debug.apk
        if ($LASTEXITCODE -ne 0) {
            throw "Debug-App konnte auf Gerät $DeviceSerial nicht aktualisiert werden."
        }
        & $adb -s $DeviceSerial install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
        if ($LASTEXITCODE -ne 0) {
            throw "Test-App konnte auf Gerät $DeviceSerial nicht aktualisiert werden."
        }

        $instrumentOutput = & $adb -s $DeviceSerial shell am instrument -w de.wandern.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
        $instrumentExitCode = $LASTEXITCODE
        $instrumentOutput | ForEach-Object { Write-Output $_ }
        $instrumentText = $instrumentOutput -join [Environment]::NewLine
        if ($instrumentExitCode -ne 0 -or $instrumentText -notmatch "OK \(\d+ tests?\)") {
            throw "Instrumentierte Tests auf Gerät $DeviceSerial fehlgeschlagen."
        }

        # ActivityScenario schließt die getestete Activity regulär. Für lokale
        # Entwicklung danach wieder die echte App öffnen, damit das nicht wie
        # ein Absturz aussieht und direkt manuell weitergetestet werden kann.
        & $adb -s $DeviceSerial shell am start -n de.wandern.app/.ui.MainActivity | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Wandern konnte nach dem Testlauf nicht wieder geöffnet werden."
        }
    }
} finally {
    Pop-Location
}
