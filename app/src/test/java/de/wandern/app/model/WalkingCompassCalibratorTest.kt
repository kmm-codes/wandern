package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingCompassCalibratorTest {
    @Test
    fun learnsOffsetAfterStraightWalk() {
        val calibrator = WalkingCompassCalibrator(requiredDistanceMeters = 10.0, requiredSamples = 3)
        var progress = calibrator.update(point(48.0, 8.0), phoneHeadingDegrees = 70f)
        progress = calibrator.update(point(48.00005, 8.0), phoneHeadingDegrees = 71f)
        progress = calibrator.update(point(48.00010, 8.0), phoneHeadingDegrees = 69f)

        assertEquals(WalkingCompassCalibrator.State.READY, progress.state)
        assertEquals(20f, progress.offsetDegrees!!, 1f)
    }

    @Test
    fun waitsForReliableMovement() {
        val calibrator = WalkingCompassCalibrator(requiredDistanceMeters = 1.0, requiredSamples = 1)

        val slow = calibrator.update(point(48.0, 8.0, speed = 0.2f), phoneHeadingDegrees = 70f)
        val inaccurate = calibrator.update(point(48.0, 8.0, accuracy = 50f), phoneHeadingDegrees = 70f)

        assertEquals(WalkingCompassCalibrator.State.WAITING_FOR_GPS, slow.state)
        assertEquals(WalkingCompassCalibrator.State.WAITING_FOR_GPS, inaccurate.state)
        assertFalse(slow.state == WalkingCompassCalibrator.State.READY)
    }

    @Test
    fun resetsWhenCourseIsNoLongerStraight() {
        val calibrator = WalkingCompassCalibrator(requiredDistanceMeters = 1.0, requiredSamples = 2)
        calibrator.update(point(48.0, 8.0, bearing = 90f), phoneHeadingDegrees = 70f)
        val progress = calibrator.update(point(48.00005, 8.0, bearing = 140f), phoneHeadingDegrees = 120f)

        assertEquals(WalkingCompassCalibrator.State.WALK_STRAIGHT, progress.state)
        assertEquals(1, progress.sampleCount)
        assertTrue(progress.distanceMeters < 0.1)
    }

    private fun point(
        latitude: Double,
        longitude: Double,
        bearing: Float = 90f,
        speed: Float = 1.5f,
        accuracy: Float = 5f,
    ) = TrackPoint(
        latitude = latitude,
        longitude = longitude,
        bearingDegrees = bearing,
        speedMetersPerSecond = speed,
        accuracyMeters = accuracy,
    )
}
