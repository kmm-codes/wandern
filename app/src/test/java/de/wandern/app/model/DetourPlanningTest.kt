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
        assertEquals(detour.points, result.detourTrack.points)
        assertEquals(200.0, result.departureDistanceMeters, 0.0)
        assertEquals(2, result.track.segments.size)
    }

    @Test
    fun displayRouteKeepsWalkedPrefixAndTailButOmitsReplacedSection() {
        val path = RoutePath(route)
        val departure = 300.0
        val rejoin = 1_400.0

        val displayed = DetourPlanner.originalRouteOutsideDetour(route, departure, rejoin)

        assertEquals(2, displayed.segments.size)
        assertEquals(route.points.first(), displayed.segments.first().first())
        assertEquals(path.pointAt(departure), displayed.segments.first().last())
        assertEquals(path.pointAt(rejoin), displayed.segments.last().first())
        assertEquals(route.points.last(), displayed.segments.last().last())
        assertTrue(displayed.points.none { point ->
            point.longitude > path.pointAt(departure).longitude &&
                point.longitude < path.pointAt(rejoin).longitude
        })
    }

    @Test
    fun roundTripRejoinCandidatesStayAheadInRouteOrder() {
        val points = (0..72).map { index ->
            val angle = index / 72.0 * Math.PI * 2.0
            TrackPoint(
                latitude = 48.0 + kotlin.math.sin(angle) * 0.012,
                longitude = 8.0 + kotlin.math.cos(angle) * 0.018,
            )
        }
        val roundTrip = GpxTrack("Rundweg", listOf(points), activityType = ActivityType.HIKING)
        val path = RoutePath(roundTrip)
        val corridor = DetourPlanner.corridor(roundTrip, path.totalDistanceMeters * 0.35, 300.0)

        val rejoinDistances = DetourPlanner.rejoinDistances(roundTrip, corridor)

        assertTrue(rejoinDistances.isNotEmpty())
        assertEquals(rejoinDistances.sorted(), rejoinDistances)
        assertTrue(rejoinDistances.all { it > corridor.endDistanceMeters })
        assertTrue(rejoinDistances.all { it <= path.totalDistanceMeters })
    }
}
