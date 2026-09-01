package de.wandern.app.model

import de.wandern.app.data.PlaceSearchResult
import java.util.Locale

/** Keeps strong name matches together and prefers nearby places within each group. */
object PlaceSearchRanker {
    fun rank(
        query: String,
        results: List<PlaceSearchResult>,
        reference: TrackPoint?,
    ): List<PlaceSearchResult> {
        if (reference == null || results.size < 2) return results

        val normalizedQuery = normalize(query)
        return results.withIndex()
            .sortedWith(
                compareBy<IndexedValue<PlaceSearchResult>> {
                    textMatchTier(normalizedQuery, it.value.displayName)
                }.thenBy {
                    GeoMath.distanceMeters(
                        reference,
                        TrackPoint(it.value.latitude, it.value.longitude),
                    )
                }.thenBy { it.index },
            )
            .map(IndexedValue<PlaceSearchResult>::value)
    }

    private fun textMatchTier(query: String, displayName: String): Int {
        if (query.isEmpty()) return 0
        val primaryName = normalize(displayName.substringBefore(','))
        val completeName = normalize(displayName)
        return when {
            primaryName == query -> 0
            primaryName.startsWith(query) -> 1
            primaryName.contains(query) -> 2
            completeName.contains(query) -> 3
            else -> 4
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
}
