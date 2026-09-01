# Einfacher Akku-Testplan

Ziel: Den Verbrauch beim Wandern reproduzierbar messen und gleichzeitig prüfen,
dass die Aufzeichnung bei gesperrtem Bildschirm vollständig und genau bleibt.

## Vorbereitung

1. Verwende für alle Läufe dasselbe Telefon und dieselbe Strecke.
2. Lade den Akku auf mindestens 80 Prozent und lasse das Telefon vor jedem Lauf
   etwa zehn Minuten abkühlen.
3. Lade die benötigte Karte vorher offline. Schalte mobile Daten und WLAN während
   des Tests aus, Bluetooth nur dann, wenn es bei allen Vergleichsläufen gleich
   verwendet wird.
4. Beende andere Navigations-, Fitness- und Musik-Apps. Stelle die
   Akkuoptimierung für Wandern zunächst auf die normale Systemeinstellung.
5. Notiere Android-Version, App-Version, Akkustand und Außentemperatur.

## Test A: Aufzeichnung bei gesperrtem Bildschirm

1. Wähle eine bekannte Strecke von mindestens 60 Minuten, möglichst mit Kurven,
   Wald und einem kurzen Halt von fünf Minuten.
2. Starte die Aufzeichnung unter freiem Himmel und warte auf einen zuverlässigen
   GPS-Fix.
3. Sperre das Telefon nach einer Minute. Entsperre es unterwegs nicht.
4. Beende die Aufzeichnung erst am Ziel und notiere Start- und End-Akkustand.
5. Exportiere die GPX-Datei und kontrolliere:

   - keine längeren Lücken während gesperrtem Bildschirm;
   - keine geraden Verbindungslinien durch Kurven oder Waldstücke;
   - Start, Ziel und fünfminütiger Halt liegen an der richtigen Stelle;
   - Distanz weicht höchstens etwa 2 bis 3 Prozent von einer guten Referenz ab;
   - die Aufzeichnung lief nach mindestens 30 Minuten mit gesperrtem Bildschirm
     noch weiter.

Verbrauch pro Stunde:

`(Akkustand Start - Akkustand Ende) / Laufzeit in Stunden`

Den Lauf mindestens dreimal wiederholen und den mittleren Wert verwenden.

## Test B: Karte mit eingeschaltetem Bildschirm

Wiederhole dieselbe Strecke mit dauerhaft sichtbarer Karte. Stelle die Helligkeit
fest ein, zum Beispiel auf 30 Prozent, und deaktiviere automatische Helligkeit.
Dieser Wert wird getrennt von Test A betrachtet, weil das Display meist mehr Strom
als die eigentliche Aufzeichnung benötigt.

## Vergleich mit anderen Wander-Apps

Führe Test A und optional Test B mit jeder Vergleichs-App einzeln durch. Verwende
dieselbe Offlinekarte, dieselben Funkverbindungen und dieselben Funktionen. Eine
reine Aufzeichnung darf nicht mit Sprachnavigation oder Live-Tracking verglichen
werden.

Ergebnistabelle:

| App | Bildschirm | Laufzeit | Verbrauch | Verbrauch pro Stunde | Distanzabweichung | GPS-Lücken |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| Wandern | gesperrt | | | | | |
| Vergleich 1 | gesperrt | | | | | |
| Wandern | sichtbar | | | | | |

## Energiesparzustände gesondert prüfen

Nach dem normalen Test jeweils einen kürzeren Lauf von mindestens 30 Minuten mit
folgenden Einstellungen durchführen:

1. Android-Energiesparmodus eingeschaltet, Bildschirm gesperrt.
2. App-Akkuoption „Optimiert“, Bildschirm gesperrt.
3. Auf Xiaomi zusätzlich prüfen, ob das System die App nach 30 bis 60 Minuten
   beendet. Falls ja, denselben Lauf mit „Keine Einschränkungen“ wiederholen.

Ein Test gilt nur dann als bestanden, wenn die GPX-Aufzeichnung bis zum manuellen
Stoppen weiterläuft. Ein niedriger Verbrauch mit abgebrochener oder stark
ausgedünnter Route ist kein gültiges Ergebnis.
