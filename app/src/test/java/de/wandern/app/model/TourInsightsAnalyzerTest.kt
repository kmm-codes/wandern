package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourInsightsAnalyzerTest {
    @Test
    fun `builds elevation and speed profiles from timed points`() {
        val track = GpxTrack(
            "Test",
            listOf(
                listOf(
                    TrackPoint(48.0, 11.0, elevationMeters = 100.0, timeMillis = 0L),
                    TrackPoint(48.001, 11.0, elevationMeters = 112.0, timeMillis = 60_000L),
                    TrackPoint(48.002, 11.0, elevationMeters = 108.0, timeMillis = 120_000L),
                ),
            ),
        )

        val insights = TourInsightsAnalyzer.analyze(track)

        assertTrue(insights.hasTimeData)
        assertEquals(3, insights.elevationProfile.size)
        assertEquals(2, insights.speedProfile.size)
        assertTrue(insights.speedProfile.all { it.value > 0.0 })
    }

    @Test
    fun `reports missing time data without inventing speed`() {
        val track = GpxTrack(
            "Route",
            listOf(
                listOf(
                    TrackPoint(48.0, 11.0, elevationMeters = 100.0),
                    TrackPoint(48.001, 11.0, elevationMeters = 110.0),
                ),
            ),
        )

        val insights = TourInsightsAnalyzer.analyze(track)

        assertFalse(insights.hasTimeData)
        assertTrue(insights.speedProfile.isEmpty())
        assertEquals(2, insights.elevationProfile.size)
    }

    @Test
    fun `adds a local slope to elevation samples`() {
        val points = (0..8).map { index ->
            TrackPoint(
                latitude = 48.0 + index * 0.0001,
                longitude = 11.0,
                elevationMeters = 100.0 + index * 1.1,
                timeMillis = index * 10_000L,
            )
        }

        val profile = TourInsightsAnalyzer.analyze(GpxTrack("Steigung", listOf(points))).elevationProfile

        val middleSlope = profile[profile.size / 2].secondaryValue
        assertTrue(middleSlope != null)
        assertEquals(9.9, middleSlope!!, 1.0)
    }

    @Test
    fun `removes a single implausible speed spike from the profile`() {
        val speedsKmh = listOf(5.0, 5.2, 12.0, 5.1, 4.9)
        val points = buildList {
            var latitude = 48.0
            var timeMillis = 0L
            add(TrackPoint(latitude, 11.0, elevationMeters = 100.0, timeMillis = timeMillis))
            speedsKmh.forEach { speedKmh ->
                timeMillis += 10_000L
                latitude += speedKmh / 3.6 * 10.0 / 111_195.0
                add(TrackPoint(latitude, 11.0, elevationMeters = 100.0, timeMillis = timeMillis))
            }
        }

        val profile = TourInsightsAnalyzer.analyze(GpxTrack("Tempo", listOf(points))).speedProfile

        assertTrue(profile.maxOf { it.value } < 7.0)
    }
}
