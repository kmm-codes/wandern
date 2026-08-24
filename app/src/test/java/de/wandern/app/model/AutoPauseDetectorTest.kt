package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPauseDetectorTest {
    private val detector = AutoPauseDetector(
        pauseAfterMillis = 10_000L,
        resumeAfterMillis = 4_000L,
    )

    @Test
    fun pausesOnlyAfterSustainedStationaryEvidence() {
        assertFalse(detector.update(point(0L, speed = 0f)).autoPaused)
        assertFalse(detector.update(point(5_000L, speed = 0.1f)).autoPaused)

        val update = detector.update(point(10_000L, speed = 0f))

        assertTrue(update.autoPaused)
        assertEquals(AutoPauseTransition.PAUSED, update.transition)
    }

    @Test
    fun briefStopDoesNotPause() {
        detector.update(point(0L, speed = 0f))
        detector.update(point(5_000L, speed = 0f))

        val update = detector.update(point(8_000L, speed = 1.2f, latitudeOffset = 0.00008))

        assertFalse(update.autoPaused)
        assertEquals(AutoPauseTransition.NONE, update.transition)
    }

    @Test
    fun resumesOnlyAfterConfirmedMovement() {
        detector.update(point(0L, speed = 0f))
        detector.update(point(5_000L, speed = 0f))
        detector.update(point(10_000L, speed = 0f))
        assertTrue(detector.update(point(12_000L, speed = 1.2f, latitudeOffset = 0.00008)).autoPaused)

        val update = detector.update(point(16_000L, speed = 1.3f, latitudeOffset = 0.00013))

        assertFalse(update.autoPaused)
        assertEquals(AutoPauseTransition.RESUMED, update.transition)
    }

    @Test
    fun gpsJitterInsideAccuracyRadiusDoesNotResume() {
        detector.update(point(0L, speed = 0f, accuracy = 20f))
        detector.update(point(5_000L, speed = 0f, accuracy = 20f))
        detector.update(point(10_000L, speed = 0f, accuracy = 20f))

        val update = detector.update(
            point(20_000L, speed = null, accuracy = 20f, latitudeOffset = 0.00015),
        )

        assertTrue(update.autoPaused)
        assertEquals(AutoPauseTransition.NONE, update.transition)
    }

    @Test
    fun inaccurateFixesCannotTriggerPause() {
        detector.update(point(0L, speed = 0f, accuracy = 80f))
        detector.update(point(10_000L, speed = 0f, accuracy = 80f))

        val update = detector.update(point(20_000L, speed = 0f, accuracy = 80f))

        assertFalse(update.autoPaused)
        assertEquals(AutoPauseTransition.NONE, update.transition)
    }

    private fun point(
        timeMillis: Long,
        speed: Float?,
        accuracy: Float = 5f,
        latitudeOffset: Double = 0.0,
    ) = TrackPoint(
        latitude = 48.0 + latitudeOffset,
        longitude = 8.0,
        timeMillis = timeMillis,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
    )
}
