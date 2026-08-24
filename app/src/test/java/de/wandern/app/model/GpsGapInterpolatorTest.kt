package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsGapInterpolatorTest {
    @Test
    fun `interpolates coordinates elevation and time between good fixes`() {
        val start = TrackPoint(48.0, 8.0, 100.0, 1_000L, 8f)
        val end = TrackPoint(48.01, 8.02, 120.0, 21_000L, 10f)

        val points = GpsGapInterpolator.between(start, end, intervalMillis = 5_000L)

        assertEquals(3, points.size)
        assertEquals(48.0025, points.first().latitude, 0.000001)
        assertEquals(8.005, points.first().longitude, 0.000001)
        assertEquals(105.0, points.first().elevationMeters!!, 0.001)
        assertEquals(6_000L, points.first().timeMillis)
        assertTrue(points.all { it.isInterpolated })
    }

    @Test
    fun `does not add points for a short interval`() {
        val start = TrackPoint(48.0, 8.0, timeMillis = 1_000L)
        val end = TrackPoint(48.01, 8.01, timeMillis = 5_000L)

        assertTrue(GpsGapInterpolator.between(start, end).isEmpty())
    }

    @Test
    fun `caps very long gaps`() {
        val start = TrackPoint(48.0, 8.0, timeMillis = 0L)
        val end = TrackPoint(49.0, 9.0, timeMillis = 3_600_000L)

        assertEquals(10, GpsGapInterpolator.between(start, end, maxPoints = 10).size)
    }
}
