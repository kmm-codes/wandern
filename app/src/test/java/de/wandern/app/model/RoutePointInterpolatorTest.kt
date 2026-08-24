package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutePointInterpolatorTest {
    @Test
    fun interpolatesPositionElevationAndTimeAlongLeg() {
        val start = TrackPoint(49.0, 8.0, elevationMeters = 100.0, timeMillis = 1_000L)
        val end = TrackPoint(49.0, 8.01, elevationMeters = 200.0, timeMillis = 3_000L)
        val track = GpxTrack("Test", listOf(listOf(start, end)))
        val halfDistance = GeoMath.distanceMeters(start, end) / 2.0

        val point = RoutePointInterpolator.pointAtDistance(track, halfDistance)!!

        assertEquals(49.0, point.latitude, 0.000001)
        assertEquals(8.005, point.longitude, 0.000001)
        assertEquals(150.0, point.elevationMeters!!, 0.001)
        assertEquals(2_000L, point.timeMillis)
    }

    @Test
    fun ignoresGapBetweenSegmentsAndClampsAtEnd() {
        val first = TrackPoint(49.0, 8.0)
        val second = TrackPoint(49.0, 8.001)
        val third = TrackPoint(50.0, 9.0)
        val fourth = TrackPoint(50.0, 9.001)
        val track = GpxTrack("Test", listOf(listOf(first, second), listOf(third, fourth)))
        val firstLeg = GeoMath.distanceMeters(first, second)

        val secondSegmentStart = RoutePointInterpolator.pointAtDistance(track, firstLeg + 0.01)!!
        val afterEnd = RoutePointInterpolator.pointAtDistance(track, Double.MAX_VALUE)!!

        assertEquals(third.latitude, secondSegmentStart.latitude, 0.0001)
        assertEquals(fourth, afterEnd)
    }

    @Test
    fun returnsNullForEmptyTrack() {
        assertNull(RoutePointInterpolator.pointAtDistance(GpxTrack.empty(), 100.0))
    }
}
