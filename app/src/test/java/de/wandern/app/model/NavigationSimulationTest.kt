package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSimulationTest {
    private val route = GpxTrack(
        name = "simulation route",
        segments = listOf(
            listOf(
                TrackPoint(49.0, 8.0, 100.0),
                TrackPoint(49.0, 8.01, 110.0),
                TrackPoint(48.995, 8.01, 120.0),
            ),
        ),
        activityType = ActivityType.HIKING,
        navigationManeuvers = listOf(
            NavigationManeuver(
                type = NavigationManeuverType.RIGHT,
                point = TrackPoint(49.0, 8.01, 110.0),
                distanceAlongRouteMeters = 730.0,
                turnAngleDegrees = 90.0,
            ),
        ),
    )

    @Test
    fun `follow route creates speed-aware samples without overshooting`() {
        val samples = NavigationSimulation.followRoute(route, 0.0, 1_000.0, 5.0)

        assertTrue(samples.size > 80)
        assertEquals(5.0 / 3.6, samples.last().point.speedMetersPerSecond!!.toDouble(), 0.01)
        assertEquals(1_000.0, routeDistance(samples), 3.0)
        assertTrue(samples.last().elapsedMillis in 716_000L..724_000L)
    }

    @Test
    fun `deviation follows route to next turn and then leaves it`() {
        val samples = NavigationSimulation.deviateAtNextTurn(
            route = route,
            fromDistanceMeters = 500.0,
            direction = SimulationTurnDirection.LEFT,
            deviationDistanceMeters = 500.0,
            speedKilometersPerHour = 5.0,
        )

        val routePath = RoutePath(route)
        val departure = routePath.pointAt(730.0)
        val departureIndex = samples.indices.minBy { index ->
            GeoMath.distanceMeters(samples[index].point, departure)
        }
        assertTrue(departureIndex > 10)
        assertTrue(GeoMath.distanceToTrackMeters(samples.last().point, route)!! > 400.0)
        assertEquals(730.0 - 500.0 + 500.0, routeDistance(samples), 12.0)
    }

    @Test
    fun `rejoin ends on a forward route point`() {
        val offRoute = TrackPoint(49.002, 8.006)
        val samples = NavigationSimulation.rejoinRoute(route, offRoute, 400.0, 5.0)

        assertTrue(samples.size > 2)
        assertTrue(GeoMath.distanceToTrackMeters(samples.last().point, route)!! < 0.5)
        assertTrue(RoutePath(route).nearestDistanceAlongRoute(samples.last().point)!! >= 375.0)
    }

    private fun routeDistance(samples: List<SimulatedLocationSample>): Double = samples
        .map(SimulatedLocationSample::point)
        .zipWithNext()
        .sumOf { (from, to) -> GeoMath.distanceMeters(from, to) }
}
