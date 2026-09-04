package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourPlanningTest {
    private val route = GpxTrack(
        name = "Test",
        segments = listOf((0..30).map { index -> TrackPoint(48.0, 8.0 + index * 0.001) }),
        activityType = ActivityType.HIKING,
    )
    private val routePath = RoutePath(route)
    private val plannedCorridor = DetourPlanner.corridor(route, DEPARTURE_METERS, 250.0)

    @Test
    fun corridorStartsAheadAndProvidesNoGoSamples() {
        val corridor = DetourPlanner.corridor(route, 300.0, 250.0)

        assertTrue(corridor.startDistanceMeters >= 330.0)
        assertTrue(corridor.endDistanceMeters > corridor.startDistanceMeters)
        assertTrue(corridor.noGoPoints.size > 2)
        assertTrue(corridor.points.size >= 2)
    }

    @Test
    fun noGoSamplingKeepsBlockedLineCoveredEvenWithFarApartPoints() {
        val blocked = listOf(TrackPoint(48.0, 8.0), TrackPoint(48.0, 8.005))

        val noGoPoints = DetourPlanner.noGoPointsAlong(blocked, widthMeters = 30)

        val spacing = noGoPoints.map { it.point }.zipWithNext { a, b -> GeoMath.distanceMeters(a, b) }
        assertTrue(noGoPoints.size >= 10)
        assertTrue(spacing.all { it <= 36.0 })
        assertTrue(noGoPoints.all { it.radiusMeters == 30 })
        assertEquals(blocked.first().longitude, noGoPoints.first().point.longitude, 0.000001)
        assertEquals(blocked.last().longitude, noGoPoints.last().point.longitude, 0.000001)
    }

    @Test
    fun noGoSamplingMatchesTheCorridorItWasExtractedFrom() {
        val corridor = DetourPlanner.corridor(route, 300.0, 250.0)

        val resampled = DetourPlanner.noGoPointsAlong(corridor.points, corridor.widthMeters)

        assertEquals(corridor.noGoPoints, resampled)
    }

    @Test
    fun noGoSamplingIgnoresDegenerateInput() {
        assertTrue(DetourPlanner.noGoPointsAlong(emptyList()).isEmpty())
        assertEquals(1, DetourPlanner.noGoPointsAlong(listOf(TrackPoint(48.0, 8.0))).size)
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

    @Test
    fun routeAccessCandidatesStartNearRecommendedForwardRejoin() {
        val progress = 500.0
        val position = TrackPoint(48.002, 8.009)

        val rejoinDistances = DetourPlanner.rejoinDistances(route, position, progress)

        assertTrue(rejoinDistances.isNotEmpty())
        assertTrue(rejoinDistances.size <= 3)
        assertTrue(rejoinDistances.all { it >= progress - 25.0 })
    }

    @Test
    fun theSameWayWithALaterRejoinIsNotASecondProposal() {
        val earlier = detourVia(NORTH_STREET_LATITUDE, NEAR_REJOIN_METERS)
        val later = sameWayWithLaterRejoin(NORTH_STREET_LATITUDE, NEAR_REJOIN_METERS, FAR_REJOIN_METERS)

        assertTrue(DetourPlanner.trackFollows(earlier.detourTrack, later.track))
        assertTrue(DetourPlanner.trackFollows(later.detourTrack, earlier.track))
        assertTrue(DetourPlanner.takesSameWay(earlier, later))
        assertFalse(DetourPlanner.isDistinctProposal(later, listOf(earlier)))
        assertFalse(DetourPlanner.isDistinctProposal(earlier, listOf(later)))
    }

    @Test
    fun proposalsOnDifferentStreetsStayDistinct() {
        val north = detourVia(NORTH_STREET_LATITUDE, NEAR_REJOIN_METERS)
        val south = detourVia(SOUTH_STREET_LATITUDE, NEAR_REJOIN_METERS)

        assertFalse(DetourPlanner.takesSameWay(north, south))
        assertTrue(DetourPlanner.isDistinctProposal(south, listOf(north)))
    }

    @Test
    fun runningStraightToTheDestinationStaysDistinctFromARejoiningWay() {
        val direct = directToDestinationVia(NORTH_STREET_LATITUDE)
        val rejoining = detourVia(SOUTH_STREET_LATITUDE, NEAR_REJOIN_METERS)

        assertTrue(direct.directToDestination)
        assertFalse(DetourPlanner.takesSameWay(direct, rejoining))
        assertTrue(DetourPlanner.isDistinctProposal(rejoining, listOf(direct)))
        assertTrue(DetourPlanner.isDistinctProposal(direct, listOf(rejoining)))
    }

    @Test
    fun distinctnessComparesAgainstEveryProposalFoundSoFar() {
        val north = detourVia(NORTH_STREET_LATITUDE, NEAR_REJOIN_METERS)
        val south = detourVia(SOUTH_STREET_LATITUDE, NEAR_REJOIN_METERS)
        val southAgain = sameWayWithLaterRejoin(SOUTH_STREET_LATITUDE, NEAR_REJOIN_METERS, FAR_REJOIN_METERS)

        assertTrue(DetourPlanner.isDistinctProposal(south, listOf(north)))
        assertFalse(DetourPlanner.isDistinctProposal(southAgain, listOf(north, south)))
    }

    /** Detour that leaves the route at [DEPARTURE_METERS], follows a parallel street and rejoins. */
    private fun detourVia(latitude: Double, rejoinDistanceMeters: Double): DetourRouteCandidate =
        DetourPlanner.combine(
            route = route,
            currentProgressMeters = DEPARTURE_METERS,
            corridor = plannedCorridor,
            detour = detourTrack(parallelStreet(latitude, rejoinDistanceMeters)),
            rejoinDistanceMeters = rejoinDistanceMeters,
        )

    /**
     * The answer the router typically gives for a later rejoin point: the same parallel street plus
     * a stretch along the original route.
     */
    private fun sameWayWithLaterRejoin(
        latitude: Double,
        rejoinDistanceMeters: Double,
        laterRejoinDistanceMeters: Double,
    ): DetourRouteCandidate = DetourPlanner.combine(
        route = route,
        currentProgressMeters = DEPARTURE_METERS,
        corridor = plannedCorridor,
        detour = detourTrack(
            parallelStreet(latitude, rejoinDistanceMeters) +
                routePath.slice(rejoinDistanceMeters, laterRejoinDistanceMeters),
        ),
        rejoinDistanceMeters = laterRejoinDistanceMeters,
    )

    private fun directToDestinationVia(latitude: Double): DetourRouteCandidate = DetourPlanner.combine(
        route = route,
        currentProgressMeters = DEPARTURE_METERS,
        corridor = plannedCorridor,
        detour = detourTrack(parallelStreet(latitude, routePath.totalDistanceMeters)),
        rejoinDistanceMeters = routePath.totalDistanceMeters,
        directToDestination = true,
    )

    private fun parallelStreet(latitude: Double, rejoinDistanceMeters: Double): List<TrackPoint> {
        val departure = routePath.pointAt(DEPARTURE_METERS)
        val rejoin = routePath.pointAt(rejoinDistanceMeters)
        return listOf(
            departure,
            TrackPoint(latitude, departure.longitude),
            TrackPoint(latitude, rejoin.longitude),
            rejoin,
        )
    }

    private fun detourTrack(points: List<TrackPoint>) = GpxTrack(
        name = "Umleitung",
        segments = listOf(points),
        activityType = ActivityType.HIKING,
    )

    @Test
    fun combinesRoutedConnectorWithOriginalTail() {
        val path = RoutePath(route)
        val progress = 300.0
        val rejoin = 1_100.0
        val position = TrackPoint(48.002, 8.004)
        val connector = GpxTrack(
            "Zur Route",
            listOf(listOf(position, TrackPoint(48.001, 8.010), path.pointAt(rejoin))),
            activityType = ActivityType.HIKING,
        )

        val result = DetourPlanner.combineRejoin(route, progress, connector, rejoin)

        assertEquals(connector.points, result.detourTrack.points)
        assertEquals(route.points.last(), result.track.points.last())
        assertEquals(rejoin - progress, result.skippedRouteMeters, 0.001)
        assertEquals(2, result.track.segments.size)
    }

    private companion object {
        const val DEPARTURE_METERS = 300.0
        const val NEAR_REJOIN_METERS = 710.0
        const val FAR_REJOIN_METERS = 860.0

        /** Roughly 130 m north of the route, well beyond the 30 m the comparison tolerates. */
        const val NORTH_STREET_LATITUDE = 48.0012

        /** Roughly 65 m south of the route, so both parallel streets are clearly different ways. */
        const val SOUTH_STREET_LATITUDE = 47.9994
    }
}
