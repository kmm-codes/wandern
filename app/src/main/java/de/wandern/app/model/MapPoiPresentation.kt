package de.wandern.app.model

import de.wandern.app.localization.AppLanguage

data class MapPoiPresentation(
    val title: String,
    val category: String,
)

object MapPoiPresenter {
    fun present(
        name: String?,
        poiClass: String?,
        subclass: String?,
        language: AppLanguage = AppLanguage.ENGLISH,
    ): MapPoiPresentation {
        val category = categoryLabel(subclass, language) ?: categoryLabel(poiClass, language)
            ?: humanize(subclass ?: poiClass ?: "point_of_interest")
        return MapPoiPresentation(
            title = name?.trim()?.takeIf(String::isNotEmpty) ?: category,
            category = category,
        )
    }

    private fun categoryLabel(value: String?, language: AppLanguage): String? = when (value?.lowercase()) {
        "information" -> localized(language, "Information", "Information")
        "guidepost" -> localized(language, "Guidepost", "Wegweiser")
        "board" -> localized(language, "Information board", "Informationstafel")
        "map" -> localized(language, "Overview map", "Übersichtskarte")
        "route_marker" -> localized(language, "Route marker", "Routenmarkierung")
        "viewpoint" -> localized(language, "Viewpoint", "Aussichtspunkt")
        "alpine_hut" -> localized(language, "Alpine hut", "Berghütte")
        "wilderness_hut" -> localized(language, "Wilderness hut", "Schutzhütte")
        "shelter" -> localized(language, "Shelter", "Unterstand")
        "picnic_site" -> localized(language, "Picnic site", "Picknickplatz")
        "drinking_water" -> localized(language, "Drinking water", "Trinkwasser")
        "spring" -> localized(language, "Spring", "Quelle")
        "toilets", "toilet" -> localized(language, "Toilet", "Toilette")
        "bench" -> localized(language, "Bench", "Sitzbank")
        "parking" -> localized(language, "Parking", "Parkplatz")
        "camp_site", "campsite" -> localized(language, "Campsite", "Campingplatz")
        "cafe" -> localized(language, "Café", "Café")
        "restaurant" -> localized(language, "Restaurant", "Restaurant")
        "fast_food" -> localized(language, "Fast food", "Imbiss")
        "lodging", "hotel", "guest_house" -> localized(language, "Accommodation", "Unterkunft")
        "attraction" -> localized(language, "Attraction", "Sehenswürdigkeit")
        "castle" -> localized(language, "Castle or palace", "Burg oder Schloss")
        "museum" -> localized(language, "Museum", "Museum")
        "place_of_worship" -> localized(language, "Place of worship", "Gebetsstätte")
        "hospital" -> localized(language, "Hospital", "Krankenhaus")
        "pharmacy" -> localized(language, "Pharmacy", "Apotheke")
        "fuel" -> localized(language, "Gas station", "Tankstelle")
        "bicycle_rental" -> localized(language, "Bicycle rental", "Fahrradverleih")
        "railway", "station" -> localized(language, "Train station", "Bahnhof")
        "bus", "bus_stop" -> localized(language, "Bus stop", "Bushaltestelle")
        else -> null
    }

    private fun localized(language: AppLanguage, english: String, german: String): String =
        if (language == AppLanguage.GERMAN) german else english

    private fun humanize(value: String): String = value
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { it.titlecase() }
}
