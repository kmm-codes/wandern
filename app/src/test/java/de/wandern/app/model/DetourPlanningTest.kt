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
    fun proposalsAlongTheSameStreetCountAsOneSuggestion() {
        val first = straightTrack("A", latitude = 48.0, points = 21, longitudeStep = 0.0005)
        val nearlyTheSame = straightTrack("B", latitude = 48.0001, points = 11, longitudeStep = 0.001)

        assertTrue(DetourPlanner.tracksOverlap(first, nearlyTheSame))
        assertFalse(DetourPlanner.isDistinctProposal(nearlyTheSame, listOf(first)))
    }

    @Test
    fun proposalsOnParallelStreetsStayDistinct() {
        val first = straightTrack("A", latitude = 48.0, points = 21, longitudeStep = 0.0005)
        val parallel = straightTrack("B", latitude = 48.0009, points = 21, longitudeStep = 0.0005)

        assertFalse(DetourPlanner.tracksOverlap(first, parallel))
        assertTrue(DetourPlanner.isDistinctProposal(parallel, listOf(first)))
    }

    @Test
    fun clearlyLongerProposalsStayDistinctEvenOnTheSameLine() {
        val first = straightTrack("A", latitude = 48.0, points = 21, longitudeStep = 0.0005)
        val longer = straightTrack("B", latitude = 48.0, points = 31, longitudeStep = 0.0005)

        assertFalse(DetourPlanner.tracksOverlap(first, longer))
        assertTrue(DetourPlanner.isDistinctProposal(longer, listOf(first)))
    }

    @Test
    fun distinctnessComparesAgainstEveryProposalFoundSoFar() {
        val first = straightTrack("A", latitude = 48.0, points = 21, longitudeStep = 0.0005)
        val parallel = straightTrack("B", latitude = 48.0009, points = 21, longitudeStep = 0.0005)
        val nearlyTheSecond = straightTrack("C", latitude = 48.001, points = 21, longitudeStep = 0.0005)

        assertFalse(DetourPlanner.isDistinctProposal(nearlyTheSecond, listOf(first, parallel)))
    }

    private fun straightTrack(
        name: String,
        latitude: Double,
        points: Int,
        longitudeStep: Double,
    ) = GpxTrack(
        name = name,
        segments = listOf((0 until points).map { index -> TrackPoint(latitude, 8.0 + index * longitudeStep) }),
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
}
