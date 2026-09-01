package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingClockTest {
    @Test
    fun `total time continues while auto pause stops moving time`() {
        val clock = RecordingClock()
        clock.start(nowElapsedRealtimeMillis = 1_000L)
        clock.setMoving(true, nowElapsedRealtimeMillis = 2_000L)
        clock.setMoving(false, nowElapsedRealtimeMillis = 12_000L)

        val paused = clock.snapshot(nowElapsedRealtimeMillis = 42_000L)

        assertEquals(41_000L, paused.totalMillis)
        assertEquals(10_000L, paused.movingMillis)
        assertEquals(31_000L, paused.pauseMillis)
        assertFalse(clock.isMoving)
    }

    @Test
    fun `moving time continues again after motion resumes`() {
        val clock = RecordingClock()
        clock.start(nowElapsedRealtimeMillis = 1_000L)
        clock.setMoving(true, nowElapsedRealtimeMillis = 2_000L)
        clock.setMoving(false, nowElapsedRealtimeMillis = 12_000L)
        clock.setMoving(true, nowElapsedRealtimeMillis = 42_000L)

        val resumed = clock.snapshot(nowElapsedRealtimeMillis = 47_000L)

        assertEquals(46_000L, resumed.totalMillis)
        assertEquals(15_000L, resumed.movingMillis)
        assertTrue(clock.isMoving)
    }

    @Test
    fun `ui projection advances only total time during auto pause`() {
        val snapshot = TrackingSnapshot(
            state = RecordingState.RECORDING,
            stats = TrackStats(
                durationMillis = 60_000L,
                movingDurationMillis = 45_000L,
            ),
            autoPaused = true,
            capturedAtElapsedRealtimeMillis = 100_000L,
            movementTimeRunning = false,
        )

        val projected = projectRecordingDurations(snapshot, nowElapsedRealtimeMillis = 105_000L)

        assertEquals(65_000L, projected.totalMillis)
        assertEquals(45_000L, projected.movingMillis)
        assertEquals(20_000L, projected.pauseMillis)
    }

    @Test
    fun `ui projection advances both clocks during movement`() {
        val snapshot = TrackingSnapshot(
            state = RecordingState.RECORDING,
            stats = TrackStats(
                durationMillis = 60_000L,
                movingDurationMillis = 45_000L,
            ),
            capturedAtElapsedRealtimeMillis = 100_000L,
            movementTimeRunning = true,
        )

        val projected = projectRecordingDurations(snapshot, nowElapsedRealtimeMillis = 105_000L)

        assertEquals(65_000L, projected.totalMillis)
        assertEquals(50_000L, projected.movingMillis)
    }
}
