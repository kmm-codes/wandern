# Wandern

Eine lokale Android-App für Touren und Outdoor-Aktivitäten ohne Konto, Backend
oder Abo. Sie importiert und verwaltet GPX-Touren, speichert Kartenbereiche
offline und zeichnet Aktivitäten als GPX auf.

## Funktionen

- GPX-Tracks und GPX-Routen einzeln oder gesammelt importieren
- GPX-Dateien direkt über „Öffnen mit“ annehmen
- geplante und aufgezeichnete Touren in einer gemeinsamen Bibliothek verwalten
- Orte suchen und Touren online aus Start, Ziel und Zwischenzielen passend zur
  Sportart berechnen; Punkte können auch über Karte oder aktuelle Position
  gesetzt und die Routenrichtung vertauscht werden
- bis zu drei alternative Strecken vergleichen; neben dem einfachen Hinweg
  gibt es Hin-und-zurück-Touren sowie eine Rundwegsuche mit abweichendem Rückweg
- mehrere Zwischenziele in ihrer Reihenfolge umsortieren
- interaktive Karte, Tourstatistiken und Höhen-/Geschwindigkeitsprofile anzeigen
- eigene Position als blauen Punkt mit geglätteter Kompassrichtung anzeigen
- fehlende Höhenwerte über ein digitales Höhenmodell ergänzen
- Kartenbereich einer Route nach Rückfrage offline speichern und wieder löschen
- Start und Ziel einer Route markieren
- Wandern, Radfahren, E-Bike und Laufen als Sportart einer Aufzeichnung wählen
- Aktivitäten im Vordergrunddienst aufzeichnen, pausieren und fortsetzen
- GPS-Genauigkeit anzeigen und unzuverlässige Messpunkte zurückhalten
- bei bestätigter Routenabweichung offline Richtung und Luftlinienentfernung zu
  einem fortschrittsnahen Wiedereinstieg anzeigen
- längere GPS-Lücken nach Wiederkehr eines zuverlässigen Signals interpolieren
- aufgezeichnete Touren umbenennen, erneut öffnen und als GPX exportieren
- Gehzeit für Wanderungen anhand von Steigung, Fitnessprofil, Ermüdung und
  Pausenpuffer schätzen

Alle Touren und Aufzeichnungen werden lokal auf dem Gerät gespeichert. Für die
Online-Karte werden Kartendaten von OpenFreeMap geladen. Wenn eine GPX-Datei
keine Höhenwerte enthält, werden ausgewählte Routenkoordinaten an die
Open-Meteo Elevation API übertragen und die ergänzten Werte anschließend lokal
gespeichert. Beim Berechnen einer neuen Route werden die gesetzten Start-, Ziel-
und Zwischenkoordinaten an den öffentlichen BRouter-Dienst übertragen. Eine
explizit abgesendete Ortssuche überträgt den Suchtext an den öffentlichen
Nominatim-Dienst; die App verwendet dafür kein Autocomplete und begrenzt die
Anfragen gemäß dessen Nutzungsrichtlinie. Die
fertige Route wird anschließend lokal gespeichert und kann mit einer
heruntergeladenen Karte offline verwendet werden.

## Bauen

Voraussetzungen:

- JDK 17 oder neuer
- Android SDK 35

```powershell
.\gradlew.bat :app:assembleDebug
```

Die Debug-APK liegt danach unter
`app/build/outputs/apk/debug/app-debug.apk`.

## Auf einem Android-Gerät installieren

Das Entwicklerskript baut die aktuelle Debug-APK, aktualisiert die App ohne
Deinstallation oder Datenverlust und öffnet sie anschließend:

```powershell
.\run.ps1
```

Ist mehr als ein Gerät verbunden, erscheint im interaktiven Terminal eine
Auswahl. Ein Gerät kann auch direkt angegeben werden:

```powershell
.\run.ps1 -Device <ADB-SERIENNUMMER>
```

Mit `-NoBuild` wird die bereits gebaute APK installiert. `-WhatIf` zeigt die
geplanten Aktionen, ohne zu bauen, zu installieren oder die App zu starten.

## Lokal prüfen

Der normale lokale CI-Lauf umfasst JVM-Tests, Android Lint und den Debug-Build:

```powershell
.\scripts\check.ps1
```

Aus einer grafischen Entwicklungsumgebung sollte derselbe Lauf über den
Headless-Wrapper gestartet werden. Er verwendet `CreateNoWindow`, bewahrt aber
Ausgabe und Exitcode des Tests:

```powershell
.\scripts\test-hidden.ps1 check
.\scripts\test-hidden.ps1 emulator -DebugScenesOnly
.\scripts\test-hidden.ps1 navigation -LiveRoute -DeviceSerial emulator-5556
```

Mit angeschlossenem Testgerät können zusätzlich instrumentierte Android-Tests
ausgeführt werden:

```powershell
.\scripts\check.ps1 -DeviceSerial <ADB-SERIENNUMMER>
```

### Aufzeichnungs-UI ohne Tippen prüfen

Die Debug-APK akzeptiert reproduzierbare Mock-Szenen über einen expliziten
ADB-Intent. Das Skript öffnet die laufende App direkt im gewünschten Zustand
und erstellt auf Wunsch einen Screenshot; eine Klick-Automatisierung ist nicht
nötig:

```powershell
.\scripts\debug-scene.ps1 route-expanded
.\scripts\debug-scene.ps1 route-elevation-expanded
.\scripts\debug-scene.ps1 route-paused-expanded -OutputPath .\captures\paused.png
```

Verfügbare Szenen stehen über die Parametervervollständigung bzw. `Get-Help`
bereit. Mit `-NoScreenshot` wird nur der Zustand injiziert, mit `-DeviceSerial`
ein bestimmtes Gerät gewählt. Technisch entspricht der direkte Aufruf:

```powershell
adb shell am start -W -n de.wandern.app/.ui.MainActivity `
  -a de.wandern.app.DEBUG_SCENARIO --es scenario route-expanded
```

Die Mock-Schnittstelle wird durch `BuildConfig.DEBUG` abgeschirmt und verändert
weder gespeicherte Touren noch laufende Aufzeichnungssitzungen. Release-Builds
ignorieren den Debug-Intent.

### Navigation semantisch simulieren

`nav-sim.ps1` führt eine echte Aufzeichnung durch den produktiven Standortfilter,
die Persistenz, Statistik, Navigation und Abweichungserkennung. Aufrufer geben
keine Koordinatenlisten vor, sondern Bewegungsabsichten wie Distanz,
Geschwindigkeit und Abweichungsrichtung:

```powershell
# Reproduzierbare lokale Route vorbereiten und Aufzeichnung starten
.\scripts\nav-sim.ps1 fixture -Install

# Einen Kilometer mit 5 km/h entlang der Route gehen
.\scripts\nav-sim.ps1 follow -DistanceMeters 1000 -SpeedKmh 5 `
  -Screenshot .\captures\follow.png

# An der nächsten nahen Abzweigung 500 m nach rechts abweichen
.\scripts\nav-sim.ps1 deviate -Direction right -DistanceMeters 500 `
  -Screenshot .\captures\off-route.png

# Zum empfohlenen vorwärtsliegenden Routenpunkt zurückkehren
.\scripts\nav-sim.ps1 rejoin -Screenshot .\captures\rejoined.png
```

Für einen Test mit echten Such- und Routingantworten erzeugt `plan` über Photon
und BRouter eine Wanderroute:

```powershell
.\scripts\nav-sim.ps1 plan `
  -Start "Sandweier, Baden-Baden" `
  -Destination "Iffezheim"
```

`status`, `pause`, `resume`, `finish` und `discard` ergänzen den Ablauf. Bei
mehreren Geräten wählt `-DeviceSerial` den Emulator. Die CLI kommuniziert über
einen nur im Debug-Manifest exportierten Receiver und startet die sichtbare
Activity über ADB; sie benötigt keine langsame oder fragile Klicksteuerung.
Release-Builds enthalten diesen Einstiegspunkt nicht.

Der vollständige Ablauf ist als selbstprüfender Smoke-Test zusammengefasst. Er
erzeugt drei Screenshots, prüft Status und Messwerte und verwirft die
Testaufzeichnung anschließend im `finally`:

```powershell
.\scripts\test-navigation-simulation.ps1 -Install
# Optional mit echter Online-Suche und BRouter statt lokaler Fixture:
.\scripts\test-navigation-simulation.ps1 -LiveRoute
```

Für einen autonomen Lauf reserviert der Emulator-Runner zuerst Wanderns feste
AVD-Lane `Pixel_Tablet_2`, startet sie headless innerhalb des hostweiten
Vier-Emulator-Budgets und gibt Emulator sowie Lease im `finally` wieder frei:

```powershell
.\scripts\test-emulator.ps1
```

Mit `-CaptureDebugScenes` werden nach den Gerätetests zusätzlich die zentralen
Aufzeichnungszustände injiziert und unter
`.codex-device-captures/debug-scenes/` fotografiert, bevor der Emulator wieder
freigegeben wird. `-DebugScenesOnly` überspringt das vollständige Test-Gate und
führt nur Build, Installation und die visuellen Szenen aus.

Der produktneutrale Koordinator wird aus dem Geschwister-Repo
[`android-emulator-fleet`](https://github.com/kmm-codes/android-emulator-fleet)
geladen. Alternativ kann sein Pfad mit `ANDROID_EMULATOR_FLEET_HOME` gesetzt
werden. Wandern verwendet niemals einen bereits einer anderen Projekt-Lane
zugeordneten AVD.

Der Runner aktualisiert App und Test-APK mit derselben lokalen Debug-Signatur und
deinstalliert sie zwischen Läufen nicht. Deshalb erscheint die Android/Xiaomi-
Installationsabfrage normalerweise nur bei der ersten Installation oder nach
einem Signaturwechsel, etwa zwischen Debug und Release. Nach dem E2E-Lauf öffnet
das Skript die normale App wieder, weil der Test seine Activity regulär schließt.

Die Teststrategie ist bewusst lokal ausgerichtet. GitHub Actions soll später
höchstens einen kleinen, schnellen Basisschutz liefern; reale Navigations-,
Import- und Aufzeichnungsabläufe gehören in reproduzierbare lokale E2E-Tests.

Für reproduzierbare Messungen von Akkuverbrauch und GPS-Genauigkeit bei
gesperrtem Bildschirm gibt es einen [einfachen Akku-Testplan](POWER_TEST_PLAN.md).

## Navigation

Die App folgt einer gespeicherten Route, zeigt den eigenen Standort und warnt
bei Abweichungen. Während einer Aufzeichnung zeigt und spricht sie das nächste
Manöver; die Hinweise werden mit der Tour gespeichert und für ältere oder
importierte GPX-Dateien konservativ aus der Routengeometrie abgeleitet. Ohne
Internet zeigt sie zusätzlich die Richtung und Luftlinienentfernung zu einem
sinnvollen Wiedereinstieg. Neue Routen werden aktuell online über BRouter
berechnet. Eine automatische Neuberechnung des begehbaren Rückwegs gehört noch
nicht zum Funktionsumfang.

## Datenquellen

- Kartendarstellung: MapLibre Native
- Kartenstil und Kartenkacheln: OpenFreeMap / OpenStreetMap-Mitwirkende
- Ergänzte Höhendaten: Copernicus DEM GLO-90 über Open-Meteo
- Online-Routenberechnung: BRouter / OpenStreetMap-Mitwirkende
- Ortssuche: Nominatim / OpenStreetMap-Mitwirkende

Bewertete Möglichkeiten für eine spätere Satelliten-/Luftbilddarstellung sind
in [SATELLITE_MAP_OPTIONS.md](SATELLITE_MAP_OPTIONS.md) dokumentiert. Eine
Bildkartenquelle ist derzeit bewusst nicht in die App eingebunden.

## Lizenz

Der eigene Quellcode dieses Projekts steht unter der
[GNU General Public License v3.0 oder neuer](LICENSE)
(`GPL-3.0-or-later`). Jeder darf die App nutzen, untersuchen, verändern und
weitergeben. Wer eine veränderte Version oder APK verteilt, muss den
Empfängern auch den zugehörigen Quellcode unter denselben Freiheiten anbieten.

Die offiziellen APK-Releases dieses Projekts werden dauerhaft kostenlos
angeboten. Die GPL erlaubt zwar auch einen kommerziellen Vertrieb, gibt aber
allen Empfängern ausdrücklich das Recht, erhaltene Kopien kostenlos oder gegen
Entgelt weiterzugeben. Ein verteilter Fork kann deshalb nicht proprietär
gemacht werden.

Eingebundene Bibliotheken sowie Karten- und Höhendaten bleiben unter ihren
jeweiligen eigenen Lizenzen und Nutzungsbedingungen.
