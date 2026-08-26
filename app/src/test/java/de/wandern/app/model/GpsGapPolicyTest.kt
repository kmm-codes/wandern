package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsGapPolicyTest {
    @Test
    fun shortGapNeedsNoInterpolation() {
        assertEquals(
            GpsGapAction.NONE,
            decide(distanceMeters = 30.0, elapsedMillis = 10_000L),
        )
    }

    @Test
    fun plausibleBoundedGapIsInterpolated() {
        assertEquals(
            GpsGapAction.INTERPOLATE,
            decide(distanceMeters = 60.0, elapsedMillis = 30_000L),
        )
    }

    @Test
    fun implausibleGapStartsNewSegment() {
        assertEquals(
            GpsGapAction.START_NEW_SEGMENT,
            decide(distanceMeters = 500.0, elapsedMillis = 30_000L),
        )
    }

    @Test
    fun longGapStartsNewSegmentEvenWhenAverageSpeedLooksPlausible() {
        assertEquals(
            GpsGapAction.START_NEW_SEGMENT,
            decide(distanceMeters = 100.0, elapsedMillis = 120_000L),
        )
    }

    private fun decide(distanceMeters: Double, elapsedMillis: Long): GpsGapAction {
        val previous = point(0.0)
        val current = point(distanceMeters)
        return GpsGapPolicy.decide(
            previous = previous,
            current = current,
            elapsedMillis = elapsedMillis,
            activityType = ActivityType.HIKING,
        )
    }

    private fun point(northMeters: Double) = TrackPoint(
        latitude = 48.0 + northMeters / 111_320.0,
        longitude = 8.0,
        accuracyMeters = 5f,
    )
}
