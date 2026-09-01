package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourPlanningTest {
    private val route = GpxTrack(
        name = "Test",
        segments = listOf((0..30).map { index -> TrackPoint(48.0, 8.0 + index * 0.001) }),
        activityType = ActivityType.HIKING,
    )

    @Test
    fun corridorStartsAheadAndProvidesNoGoSamples() {
        val corridor = DetourPlanner.corridor(route, 300.0, 250.0)

        assertTrue(corridor.startDistanceMeters >= 330.0)
        assertTrue(corridor.endDistanceMeters > corridor.startDistanceMeters)
        assertTrue(corridor.noGoPoints.size > 2)
        assertTrue(corridor.points.size >= 2)
    }

    @Test
    fun mapPointCanBeProjectedOntoAllowedPartOfRoute() {
        val path = RoutePath(route)
        val distance = path.nearestDistanceAlongRoute(TrackPoint(48.0, 8.012), 500.0)

        assertTrue(distance != null && distance >= 500.0)
    }

    @Test
    fun combinesDetourWithOriginalTail() {
        val corridor = DetourPlanner.corridor(route, 200.0, 200.0)
        val path = RoutePath(route)
        val rejoin = corridor.endDistanceMeters + 250.0
        val detour = GpxTrack(
            "Umleitung",
            listOf(listOf(path.pointAt(200.0), TrackPoint(48.001, 8.006), path.pointAt(rejoin))),
            activityType = ActivityType.HIKING,
        )

        val result = DetourPlanner.combine(route, 200.0, corridor, detour, rejoin)

        assertEquals(route.points.last().longitude, result.track.points.last().longitude, 0.000001)
        assertTrue(result.track.points.size > detour.points.size)
    }
}
