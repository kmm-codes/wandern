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
        assertFalse(detector.update(point(0L, speed = 1.2f)).autoPaused)
        assertFalse(detector.update(point(1_000L, speed = 0f)).autoPaused)
        assertFalse(detector.update(point(6_000L, speed = 0.1f)).autoPaused)

        val update = detector.update(point(11_000L, speed = 0f))

        assertTrue(update.autoPaused)
        assertEquals(AutoPauseTransition.PAUSED, update.transition)
    }

    @Test
    fun defaultThresholdPausesAfterTwentySecondsOfReliableStationaryFixes() {
        val defaultDetector = AutoPauseDetector()
        defaultDetector.update(point(0L, speed = 1.2f), observationTimeMillis = 0L)
        defaultDetector.update(point(3_000L, speed = 0f), observationTimeMillis = 3_000L)
        defaultDetector.update(point(12_000L, speed = 0f), observationTimeMillis = 12_000L)

        val beforeThreshold = defaultDetector.update(
            point(22_000L, speed = 0f),
            observationTimeMillis = 22_000L,
        )
        val atThreshold = defaultDetector.update(
            point(23_000L, speed = 0f),
            observationTimeMillis = 23_000L,
        )

        assertFalse(beforeThreshold.autoPaused)
        assertTrue(atThreshold.autoPaused)
        assertEquals(AutoPauseTransition.PAUSED, atThreshold.transition)
    }

    @Test
    fun briefStopDoesNotPause() {
        detector.update(point(0L, speed = 1.2f))
        detector.update(point(1_000L, speed = 0f))
        detector.update(point(5_000L, speed = 0f))

        val update = detector.update(point(8_000L, speed = 1.2f, latitudeOffset = 0.00008))

        assertFalse(update.autoPaused)
        assertEquals(AutoPauseTransition.NONE, update.transition)
    }

    @Test
    fun resumesOnlyAfterConfirmedMovement() {
        detector.update(point(0L, speed = 1.2f))
        detector.update(point(1_000L, speed = 0f))
        detector.update(point(6_000L, speed = 0f))
        detector.update(point(11_000L, speed = 0f))
        assertTrue(detector.update(point(12_000L, speed = 1.2f, latitudeOffset = 0.00008)).autoPaused)

        val update = detector.update(point(16_000L, speed = 1.3f, latitudeOffset = 0.00013))

        assertFalse(update.autoPaused)
        assertEquals(AutoPauseTransition.RESUMED, update.transition)
    }

    @Test
    fun gpsJitterInsideAccuracyRadiusDoesNotResume() {
        detector.update(point(0L, speed = 1.2f, accuracy = 20f))
        detector.update(point(1_000L, speed = 0f, accuracy = 20f))
        detector.update(point(6_000L, speed = 0f, accuracy = 20f))
        detector.update(point(11_000L, speed = 0f, accuracy = 20f))

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

    @Test
    fun doesNotAutoPauseBeforeFirstRealMovement() {
        detector.update(point(0L, speed = 0f))
        detector.update(point(15_000L, speed = 0f))

        val update = detector.update(point(30_000L, speed = 0f))

        assertFalse(update.autoPaused)
        assertEquals(AutoPauseTransition.NONE, update.transition)
    }

    @Test
    fun observationClockPreventsOldLocationTimeFromTriggeringImmediatePause() {
        detector.update(point(0L, speed = 1.2f), observationTimeMillis = 1_000L)
        detector.update(point(30_000L, speed = 0f), observationTimeMillis = 2_000L)
        detector.update(point(31_000L, speed = 0f), observationTimeMillis = 3_000L)

        val update = detector.update(
            point(32_000L, speed = 0f),
            observationTimeMillis = 4_000L,
        )

        assertFalse(update.autoPaused)
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
