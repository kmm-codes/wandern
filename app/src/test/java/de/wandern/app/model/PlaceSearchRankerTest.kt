package de.wandern.app.model

import de.wandern.app.data.PlaceSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceSearchRankerTest {
    @Test
    fun equallyGoodNamesPreferTheResultNearTheSelectedStart() {
        val poland = PlaceSearchResult("Turmberg, Polen", 50.5, 18.0)
        val elsewhere = PlaceSearchResult("Turmberg, Bayern", 49.5, 11.0)
        val durlach = PlaceSearchResult("Turmberg, Durlach, Karlsruhe", 49.004, 8.484)

        val ranked = PlaceSearchRanker.rank(
            query = "Turmberg",
            results = listOf(poland, elsewhere, durlach),
            reference = TrackPoint(48.999, 8.472),
        )

        assertEquals(durlach, ranked.first())
        assertEquals(poland, ranked.last())
    }

    @Test
    fun proximityDoesNotReplaceAnExactNameWithAWeakerTextMatch() {
        val exact = PlaceSearchResult("Turmberg, Stuttgart", 48.8, 9.2)
        val nearbyPrefix = PlaceSearchResult("Turmbergstraße, Durlach", 49.001, 8.47)

        val ranked = PlaceSearchRanker.rank(
            query = "Turmberg",
            results = listOf(nearbyPrefix, exact),
            reference = TrackPoint(48.999, 8.472),
        )

        assertEquals(exact, ranked.first())
    }

    @Test
    fun searchWithoutRouteContextKeepsProviderOrder() {
        val first = PlaceSearchResult("Turmberg, Polen", 50.5, 18.0)
        val second = PlaceSearchResult("Turmberg, Durlach", 49.004, 8.484)

        assertEquals(
            listOf(first, second),
            PlaceSearchRanker.rank("Turmberg", listOf(first, second), reference = null),
        )
    }
}
