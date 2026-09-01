package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteAttributeClassifierTest {
    @Test
    fun `classifies common hiking path tags`() {
        val segment = RouteAttributeClassifier.classify(
            420.0,
            mapOf("highway" to "path", "surface" to "fine_gravel"),
        )

        assertEquals(RouteWayType.HIKING_TRAIL, segment?.wayType)
        assertEquals(RouteSurface.COMPACTED, segment?.surface)
    }

    @Test
    fun `uses track grade as surface fallback`() {
        val segment = RouteAttributeClassifier.classify(
            80.0,
            mapOf("highway" to "track", "tracktype" to "grade4"),
        )

        assertEquals(RouteWayType.TRACK, segment?.wayType)
        assertEquals(RouteSurface.NATURAL, segment?.surface)
    }

    @Test
    fun `keeps missing attributes explicitly unknown`() {
        val segment = RouteAttributeClassifier.classify(25.0, emptyMap())

        assertEquals(RouteWayType.UNKNOWN, segment?.wayType)
        assertEquals(RouteSurface.UNKNOWN, segment?.surface)
        assertNull(RouteAttributeClassifier.classify(0.0, emptyMap()))
    }
}
