# Wandern

Eine lokale Android-Wanderapp ohne Konto, Backend oder Abo. Sie importiert und
verwaltet GPX-Touren, speichert Kartenbereiche offline und zeichnet Wanderungen
als GPX auf.

## Funktionen

- GPX-Tracks und GPX-Routen einzeln oder gesammelt importieren
- GPX-Dateien direkt über „Öffnen mit“ annehmen
- geplante und aufgezeichnete Touren in einer gemeinsamen Bibliothek verwalten
- interaktive Karte, Tourstatistiken und Höhen-/Geschwindigkeitsprofile anzeigen
- fehlende Höhenwerte über ein digitales Höhenmodell ergänzen
- Kartenbereich einer Route nach Rückfrage offline speichern und wieder löschen
- Start und Ziel einer Route markieren
- Wanderungen im Vordergrunddienst aufzeichnen, pausieren und fortsetzen
- GPS-Genauigkeit anzeigen und unzuverlässige Messpunkte zurückhalten
- längere GPS-Lücken nach Wiederkehr eines zuverlässigen Signals interpolieren
- aufgezeichnete Touren umbenennen, erneut öffnen und als GPX exportieren
- Gehzeit anhand von Steigung, Fitnessprofil, Ermüdung und Pausenpuffer schätzen

Alle Touren und Aufzeichnungen werden lokal auf dem Gerät gespeichert. Für die
Online-Karte werden Kartendaten von OpenFreeMap geladen. Wenn eine GPX-Datei
keine Höhenwerte enthält, werden ausgewählte Routenkoordinaten an die
Open-Meteo Elevation API übertragen und die ergänzten Werte anschließend lokal
gespeichert.

## Bauen

Voraussetzungen:

- JDK 17 oder neuer
- Android SDK 35

```powershell
.\gradlew.bat :app:assembleDebug
```

Die Debug-APK liegt danach unter
`app/build/outputs/apk/debug/app-debug.apk`.

## Lokal prüfen

Der normale lokale CI-Lauf umfasst JVM-Tests, Android Lint und den Debug-Build:

```powershell
.\scripts\check.ps1
```

Mit angeschlossenem Testgerät können zusätzlich instrumentierte Android-Tests
ausgeführt werden:

```powershell
.\scripts\check.ps1 -DeviceSerial <ADB-SERIENNUMMER>
```

Die Teststrategie ist bewusst lokal ausgerichtet. GitHub Actions soll später
höchstens einen kleinen, schnellen Basisschutz liefern; reale Navigations-,
Import- und Aufzeichnungsabläufe gehören in reproduzierbare lokale E2E-Tests.

## Navigation

Die App folgt aktuell einer GPX-Linie, zeigt den eigenen Standort und warnt bei
Abweichungen. Abbiegehinweise und eine automatische Neuberechnung gehören noch
nicht zum Funktionsumfang.

## Datenquellen

- Kartendarstellung: MapLibre Native
- Kartenstil und Kartenkacheln: OpenFreeMap / OpenStreetMap-Mitwirkende
- Ergänzte Höhendaten: Copernicus DEM GLO-90 über Open-Meteo

## Lizenz

Für das Projekt ist noch keine Open-Source-Lizenz festgelegt. Der Quellcode ist
öffentlich einsehbar; weitergehende Nutzungsrechte werden dadurch nicht
automatisch eingeräumt.
