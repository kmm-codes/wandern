package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointOrderingTest {
    @Test
    fun viaPointDraggedAboveStartBecomesFirstPoint() {
        val points = mutableListOf("start", "via", "destination")

        assertTrue(WaypointOrdering.move(points, 1, 0))

        assertEquals(listOf("via", "start", "destination"), points)
    }

    @Test
    fun startDraggedToEndBecomesDestination() {
        val points = mutableListOf("start", "via", "destination")

        assertTrue(WaypointOrdering.move(points, 0, 2))

        assertEquals(listOf("via", "destination", "start"), points)
    }

    @Test
    fun invalidMoveDoesNotChangePoints() {
        val points = mutableListOf("start", "destination")

        assertFalse(WaypointOrdering.move(points, -1, 1))
        assertEquals(listOf("start", "destination"), points)
    }
}
