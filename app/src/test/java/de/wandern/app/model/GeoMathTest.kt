package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun `haversine distance is realistic`() {
        val distance = GeoMath.distanceMeters(
            TrackPoint(48.0, 11.0),
            TrackPoint(48.001, 11.0),
        )

        assertEquals(111.2, distance, 0.5)
    }

    @Test
    fun `distance to track uses nearest point on segment`() {
        val route = GpxTrack(
            "Test",
            listOf(listOf(TrackPoint(48.0, 11.0), TrackPoint(48.0, 11.01))),
        )

        val distance = GeoMath.distanceToTrackMeters(TrackPoint(48.001, 11.005), route)

        assertTrue(distance != null)
        assertEquals(111.2, distance!!, 0.8)
    }
}

