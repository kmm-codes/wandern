package de.wandern.app.data

import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchClientTest {
    @Test
    fun buildsExplicitLimitedJsonSearch() {
        val url = PlaceSearchClient.buildRequestUrl(
            "https://example.test/search",
            "Merkur Talstation, Baden-Baden",
            AppLanguage.GERMAN,
        )

        assertTrue(url.startsWith("https://example.test/search?"))
        assertTrue(url.contains("q=Merkur+Talstation%2C+Baden-Baden"))
        assertTrue(url.contains("limit=5"))
        assertTrue(url.contains("lang=de"))
    }

    @Test
    fun usesEnglishForTheDefaultLanguagePath() {
        val url = PlaceSearchClient.buildRequestUrl(
            "https://example.test/search",
            "Nuremberg",
            AppLanguage.ENGLISH,
        )

        assertTrue(url.contains("lang=en"))
    }

    @Test
    fun addsLocationBiasWhenAReferencePositionIsAvailable() {
        val url = PlaceSearchClient.buildRequestUrl(
            "https://example.test/search",
            "Turmberg",
            AppLanguage.GERMAN,
            TrackPoint(49.0069, 8.4037),
        )

        assertTrue(url.contains("lat=49.0069"))
        assertTrue(url.contains("lon=8.4037"))
        assertTrue(url.contains("zoom=12"))
        assertTrue(url.contains("location_bias_scale=0.1"))
    }

    @Test
    fun parsesUsableResultsAndSkipsMalformedOnes() {
        val results = PlaceSearchClient.parseResults(
            """{
                "features": [
                    {
                        "geometry":{"coordinates":[8.238,48.765],"type":"Point"},
                        "properties":{"name":"Merkur Talstation","city":"Baden-Baden","state":"Baden-Württemberg","country":"Deutschland"}
                    },
                    {
                        "geometry":{"coordinates":[8.0],"type":"Point"},
                        "properties":{"name":"Ohne Koordinate"}
                    }
                ]
            }""".trimIndent(),
        )

        assertEquals(1, results.size)
        assertEquals(
            "Merkur Talstation, Baden-Baden, Baden-Württemberg, Deutschland",
            results.single().displayName,
        )
        assertEquals(48.765, results.single().latitude, 0.0)
        assertEquals(8.238, results.single().longitude, 0.0)
    }

    @Test
    fun photonPrefixResultProducesReadablePlaceName() {
        val results = PlaceSearchClient.parseResults(
            """{
                "features": [{
                    "geometry":{"coordinates":[8.2218408,48.8058205],"type":"Point"},
                    "properties":{"name":"Haueneberstein","city":"Baden-Baden","state":"Baden-Württemberg","country":"Deutschland"}
                }]
            }""".trimIndent(),
        )

        assertEquals("Haueneberstein, Baden-Baden, Baden-Württemberg, Deutschland", results.single().displayName)
    }

    @Test
    fun buildsAndParsesReverseLookup() {
        val url = PlaceSearchClient.buildReverseRequestUrl(
            "https://example.test/reverse",
            48.765,
            8.238,
            AppLanguage.GERMAN,
        )

        assertTrue(url.startsWith("https://example.test/reverse?"))
        assertTrue(url.contains("lat=48.765"))
        assertTrue(url.contains("lon=8.238"))
        assertTrue(url.contains("accept-language=de"))
        val result = PlaceSearchClient.parseReverseResult(
            """{"display_name":"Merkur Talstation, Baden-Baden","lat":"48.765","lon":"8.238"}""",
        )
        assertEquals("Merkur Talstation, Baden-Baden", result?.displayName)
    }
}
