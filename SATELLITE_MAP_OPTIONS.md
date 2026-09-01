# Satelliten- und Luftbildkarten: Optionen

Stand: 1. September 2026

Satellitenbilder werden vorerst nicht in die App integriert. Die vorhandene
MapLibre-Basis könnte Rasterkacheln online darstellen und zusammen mit Route,
Wegpunkten und Beschriftungen als Hybridkarte anzeigen. Für eine verlässliche
Offline-Funktion muss die gewählte Quelle jedoch dauerhafte, vom Nutzer
ausgelöste Gebietsdownloads ausdrücklich erlauben.

## Realistische Quellen

### Global und hochauflösend

MapTiler Satellite ist technisch gut mit MapLibre kombinierbar und bietet
globale Rasterkacheln. Die normalen Cloud-Bedingungen erlauben allerdings nur
einen temporären persönlichen Endgeräte-Cache und untersagen ohne gesonderte
Vereinbarung umfangreiche Kacheldownloads. Für echte Offline-Gebiete wäre daher
ein passender Vertrag oder eine On-Prem-/Datenlizenz erforderlich.

- API: https://docs.maptiler.com/cloud/api/tiles/
- Satellitendaten: https://docs.maptiler.com/guides/map-tiling-hosting/data-hosting/satellite-maps/
- Cloud-Nutzungsbedingungen: https://www.maptiler.com/terms/cloud/

### Global und offen, aber grob

EOX stellt nahezu wolkenfreie Sentinel-2-Mosaike als WMS/WMTS bereit. Die
Auflösung von ungefähr 10 Metern pro Pixel reicht für Landschaft, Waldflächen
und Orientierung, aber nicht zum zuverlässigen Erkennen einzelner Wanderwege.
Neuere Jahrgänge stehen zudem unter einer nichtkommerziellen Lizenz.

- Karten und Dienste: https://maps.eox.at/
- Lizenzübersicht: https://cloudless.eox.at/pricing

### Regionale amtliche Orthofotos

Einige Bundesländer veröffentlichen hochauflösende Orthofotos als Open Data,
beispielsweise Baden-Württemberg und Bayern mit DOP20. Die Qualität ist sehr
gut, aber Abdeckung, Dienste, Quellenvermerk und Nutzungsbedingungen sind je
Bundesland verschieden. Eine solche Lösung benötigt deshalb regionale Adapter
und einen verständlichen Fallback außerhalb der jeweiligen Abdeckung.

- Baden-Württemberg: https://www.lgl-bw.de/Produkte/Geodatendienste/Luftbildprodukte/index.html
- Bayern: https://geodaten.bayern.de/opengeodata/

### Selbst gehostete Bilddaten

Technisch am zuverlässigsten für Offline-Nutzung wären lizenzierte Bilddaten in
einem eigenen Kachelarchiv oder Kartendienst. Damit wären Downloadgrenzen und
Verfügbarkeit kontrollierbar. Globale hochauflösende Daten sind dabei jedoch
teuer und sehr speicherintensiv.

## Vorgesehene Produktform, falls das Thema wieder aufgenommen wird

- Kartenwahl `Standard`, `Satellit` und `Hybrid`
- Route, Wegpunkte, POIs und Beschriftungen bleiben im Hybridmodus sichtbar
- Offline-Auswahl `Standardkarte`, `Bildkarte` oder `Beides`
- getrennte Offlinepakete pro Tour und Kartenart
- Größenprognose vor dem Download sowie ein niedrigeres maximales Zoomlevel für
  Bildkarten
- keine Nutzung oder Zwischenspeicherung inoffizieller Google-, Bing- oder
  anderer Kachel-URLs

Vor einer Implementierung muss eine globale Quelle gewählt werden, deren
Lizenz sowohl den geplanten Veröffentlichungsmodus der App als auch persistente
Offline-Gebietsdownloads abdeckt.
