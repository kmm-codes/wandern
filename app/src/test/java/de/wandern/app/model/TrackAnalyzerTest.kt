package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackAnalyzerTest {
    @Test
    fun `calculates distance duration speed and pace`() {
        val track = GpxTrack(
            "Tempo",
            listOf(
                listOf(
                    TrackPoint(48.0, 11.0, timeMillis = 0L),
                    TrackPoint(48.009, 11.0, timeMillis = 60_000L),
                ),
            ),
        )

        val stats = TrackAnalyzer.calculate(track)

        assertEquals(1_000.8, stats.distanceMeters, 2.0)
        assertEquals(60_000L, stats.durationMillis)
        assertEquals(60_000L, stats.movingDurationMillis)
        assertNotNull(stats.paceSecondsPerKilometer)
        assertEquals(59.9, stats.paceSecondsPerKilometer!!, 0.5)
    }

    @Test
    fun `reports time and count between segments as a pause`() {
        val track = GpxTrack(
            "Pause",
            listOf(
                listOf(
                    TrackPoint(48.0, 11.0, timeMillis = 0L),
                    TrackPoint(48.0005, 11.0, timeMillis = 30_000L),
                ),
                listOf(
                    TrackPoint(48.0005, 11.0, timeMillis = 300_000L),
                    TrackPoint(48.001, 11.0, timeMillis = 330_000L),
                ),
            ),
        )

        val stats = TrackAnalyzer.calculate(track)

        assertEquals(330_000L, stats.durationMillis)
        assertEquals(60_000L, stats.movingDurationMillis)
        assertEquals(270_000L, stats.pauseDurationMillis)
        assertEquals(1, stats.pauseCount)
    }

    @Test
    fun `smooths altitude noise while retaining a real climb`() {
        val elevations = listOf(100.0, 100.2, 99.8, 110.0, 110.2, 109.8, 110.0)
        val points = elevations.mapIndexed { index, elevation ->
            TrackPoint(48.0 + index * 0.0001, 11.0, elevation, index * 10_000L)
        }

        val stats = TrackAnalyzer.calculate(GpxTrack("Climb", listOf(points)))

        assertEquals(10.0, stats.ascentMeters, 1.0)
        assertEquals(0.0, stats.descentMeters, 1.0)
        assertNotNull(stats.currentSlopePercent)
    }

    @Test
    fun `retains a gradual climb made of small elevation steps`() {
        val points = (0..20).map { index ->
            TrackPoint(
                latitude = 48.0 + index * 0.0001,
                longitude = 11.0,
                elevationMeters = 100.0 + index * 0.6,
                timeMillis = index * 8_000L,
            )
        }

        val stats = TrackAnalyzer.calculate(GpxTrack("Gradual", listOf(points)))

        assertEquals(12.0, stats.ascentMeters, 2.0)
        assertEquals(0.0, stats.descentMeters, 0.5)
    }
}
