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

Mit angeschlossenem Testgerät können zusätzlich instrumentierte Android-Tests
ausgeführt werden:

```powershell
.\scripts\check.ps1 -DeviceSerial <ADB-SERIENNUMMER>
```

Für einen autonomen Lauf reserviert der Emulator-Runner zuerst Wanderns feste
AVD-Lane `Pixel_Tablet_2`, startet sie headless innerhalb des hostweiten
Vier-Emulator-Budgets und gibt Emulator sowie Lease im `finally` wieder frei:

```powershell
.\scripts\test-emulator.ps1
```

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
bei Abweichungen. Ohne Internet zeigt sie zusätzlich die Richtung und
Luftlinienentfernung zu einem sinnvollen Wiedereinstieg. Neue Routen werden
aktuell online über BRouter berechnet. Abbiegeansagen und eine automatische
Neuberechnung des begehbaren Rückwegs gehören noch nicht zum Funktionsumfang.

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
