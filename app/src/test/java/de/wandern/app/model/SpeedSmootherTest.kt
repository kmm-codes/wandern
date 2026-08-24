package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedSmootherTest {
    @Test
    fun averagesRecentGpsSpeeds() {
        val smoother = SpeedSmoother(windowMillis = 12_000L)

        assertEquals(1.0, smoother.update(point(0L, 1f))!!, 0.001)
        assertEquals(1.5, smoother.update(point(4_000L, 2f))!!, 0.001)
        assertEquals(2.0, smoother.update(point(8_000L, 3f))!!, 0.001)
    }

    @Test
    fun dropsSamplesOutsideWindow() {
        val smoother = SpeedSmoother(windowMillis = 10_000L)
        smoother.update(point(0L, 1f))

        assertEquals(3.0, smoother.update(point(11_000L, 3f))!!, 0.001)
    }

    @Test
    fun hidesSpeedWhileGpsIsUnreliable() {
        val smoother = SpeedSmoother()
        smoother.update(point(0L, 1.5f))

        assertNull(smoother.update(point(3_000L, 8f, accuracyMeters = 80f)))
    }

    @Test
    fun derivesSpeedWhenProviderDoesNotSupplyIt() {
        val smoother = SpeedSmoother()
        smoother.update(point(0L, null))

        val speed = smoother.update(
            TrackPoint(
                latitude = 48.00009,
                longitude = 8.0,
                timeMillis = 10_000L,
                accuracyMeters = 5f,
            ),
        )

        assertEquals(1.0, speed!!, 0.1)
    }

    private fun point(
        timeMillis: Long,
        speedMetersPerSecond: Float?,
        accuracyMeters: Float = 5f,
    ) = TrackPoint(
        latitude = 48.0,
        longitude = 8.0,
        timeMillis = timeMillis,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
    )
}
