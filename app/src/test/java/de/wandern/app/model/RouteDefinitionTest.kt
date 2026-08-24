package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RouteDefinitionTest {
    @Test
    fun `route definition keeps route data but removes recorded measurements`() {
        val recorded = GpxTrack(
            name = "Feierabendrunde",
            segments = listOf(
                listOf(
                    TrackPoint(
                        latitude = 48.0,
                        longitude = 8.0,
                        elevationMeters = 250.0,
                        timeMillis = 12_345L,
                        accuracyMeters = 4f,
                        speedMetersPerSecond = 1.5f,
                        isInterpolated = true,
                        bearingDegrees = 90f,
                    ),
                ),
            ),
            activityType = ActivityType.HIKING,
        )

        val route = recorded.asRouteDefinition()
        val point = route.points.single()

        assertEquals(recorded.name, route.name)
        assertEquals(recorded.activityType, route.activityType)
        assertEquals(250.0, point.elevationMeters!!, 0.001)
        assertNull(point.timeMillis)
        assertNull(point.accuracyMeters)
        assertNull(point.speedMetersPerSecond)
        assertNull(point.bearingDegrees)
        assertFalse(point.isInterpolated)
    }
}
