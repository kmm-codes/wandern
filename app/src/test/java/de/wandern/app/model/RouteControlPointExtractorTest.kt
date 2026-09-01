package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteControlPointExtractorTest {
    @Test
    fun `straight route only needs its endpoints`() {
        val points = (0..20).map { TrackPoint(48.0 + it * 0.001, 8.0) }

        val result = RouteControlPointExtractor.extract(GpxTrack("Gerade", listOf(points)))

        assertEquals(listOf(points.first(), points.last()), result)
    }

    @Test
    fun `prominent bend becomes an editable control point`() {
        val start = TrackPoint(48.0, 8.0)
        val bend = TrackPoint(48.01, 8.02)
        val end = TrackPoint(48.02, 8.0)

        val result = RouteControlPointExtractor.extract(GpxTrack("Knick", listOf(listOf(start, bend, end))))

        assertEquals(start, result.first())
        assertEquals(end, result.last())
        assertTrue(bend in result)
    }

    @Test
    fun `complex track respects point limit`() {
        val points = (0..100).map { index ->
            TrackPoint(48.0 + index * 0.0002, 8.0 + (index % 2) * 0.01)
        }

        val result = RouteControlPointExtractor.extract(
            GpxTrack("Zickzack", listOf(points)),
            maximumPoints = 10,
        )

        assertEquals(10, result.size)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }
}
