package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveTrackUpdateTest {
    @Test
    fun appendsAcceptedPointsToTheVisibleSegmentImmediately() {
        val first = TrackPoint(48.0, 8.0)
        val second = TrackPoint(48.001, 8.001)
        val track = GpxTrack("Live", listOf(listOf(first)))

        val updated = LiveTrackUpdate.append(track, segmentIndex = 0, points = listOf(second))

        assertEquals(listOf(listOf(first, second)), updated.segments)
        assertEquals(listOf(listOf(first)), track.segments)
    }

    @Test
    fun createsMissingSegmentsAfterARecordingGap() {
        val point = TrackPoint(48.001, 8.001)

        val updated = LiveTrackUpdate.append(GpxTrack.empty("Live"), segmentIndex = 1, points = listOf(point))

        assertEquals(listOf(emptyList<TrackPoint>(), listOf(point)), updated.segments)
    }

    @Test
    fun ignoresEmptyUpdates() {
        val track = GpxTrack.empty("Live")

        assertSame(track, LiveTrackUpdate.append(track, segmentIndex = 0, points = emptyList()))
    }
}
