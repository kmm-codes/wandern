package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapPlannerTest {
    @Test
    fun `adds roughly fifteen hundred meters around a compact route`() {
        val track = GpxTrack(
            "Kompakt",
            listOf(listOf(TrackPoint(48.0, 11.0), TrackPoint(48.01, 11.02))),
        )

        val plan = OfflineMapPlanner.plan(track)

        assertEquals(47.9865, plan.bounds.south, 0.001)
        assertEquals(48.0235, plan.bounds.north, 0.001)
        assertTrue(plan.bounds.west < 10.98)
        assertTrue(plan.bounds.east > 11.04)
        assertEquals(16, plan.maxZoom)
        assertTrue(plan.estimatedTileCount <= OfflineMapPlanner.DEFAULT_TILE_LIMIT)
    }

    @Test
    fun `reduces detail for a large bounding box`() {
        val track = GpxTrack(
            "Lang",
            listOf(listOf(TrackPoint(47.0, 8.0), TrackPoint(49.0, 12.0))),
        )

        val plan = OfflineMapPlanner.plan(track)

        assertTrue(plan.maxZoom < 16)
        assertTrue(plan.estimatedTileCount <= OfflineMapPlanner.DEFAULT_TILE_LIMIT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty tracks`() {
        OfflineMapPlanner.plan(GpxTrack.empty())
    }
}
