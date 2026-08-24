package de.wandern.app.model

data class MapPoiPresentation(
    val title: String,
    val category: String,
)

object MapPoiPresenter {
    fun present(name: String?, poiClass: String?, subclass: String?): MapPoiPresentation {
        val category = categoryLabel(subclass) ?: categoryLabel(poiClass)
            ?: humanize(subclass ?: poiClass ?: "point_of_interest")
        return MapPoiPresentation(
            title = name?.trim()?.takeIf(String::isNotEmpty) ?: category,
            category = category,
        )
    }

    private fun categoryLabel(value: String?): String? = when (value?.lowercase()) {
        "information" -> "Information"
        "guidepost" -> "Wegweiser"
        "board" -> "Informationstafel"
        "map" -> "Übersichtskarte"
        "route_marker" -> "Routenmarkierung"
        "viewpoint" -> "Aussichtspunkt"
        "alpine_hut" -> "Berghütte"
        "wilderness_hut" -> "Schutzhütte"
        "shelter" -> "Unterstand"
        "picnic_site" -> "Picknickplatz"
        "drinking_water" -> "Trinkwasser"
        "spring" -> "Quelle"
        "toilets", "toilet" -> "Toilette"
        "bench" -> "Sitzbank"
        "parking" -> "Parkplatz"
        "camp_site", "campsite" -> "Campingplatz"
        "cafe" -> "Café"
        "restaurant" -> "Restaurant"
        "fast_food" -> "Imbiss"
        "lodging", "hotel", "guest_house" -> "Unterkunft"
        "attraction" -> "Sehenswürdigkeit"
        "castle" -> "Burg oder Schloss"
        "museum" -> "Museum"
        "place_of_worship" -> "Gebetsstätte"
        "hospital" -> "Krankenhaus"
        "pharmacy" -> "Apotheke"
        "fuel" -> "Tankstelle"
        "bicycle_rental" -> "Fahrradverleih"
        "railway", "station" -> "Bahnhof"
        "bus", "bus_stop" -> "Bushaltestelle"
        else -> null
    }

    private fun humanize(value: String): String = value
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { it.titlecase() }
}
