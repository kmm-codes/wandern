package de.wandern.app.model

import de.wandern.app.localization.localizedSystemText
import de.wandern.app.data.RoutingNoGoPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

data class DetourCorridor(
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val widthMeters: Int,
    val points: List<TrackPoint>,
    val noGoPoints: List<RoutingNoGoPoint>,
)

data class DetourRouteCandidate(
    val track: GpxTrack,
    val detourTrack: GpxTrack,
    val departureDistanceMeters: Double,
    val rejoinDistanceMeters: Double,
    val skippedRouteMeters: Double,
    val extraDistanceMeters: Double,
    val directToDestination: Boolean,
) {
    val requiresConfirmation: Boolean = directToDestination ||
        skippedRouteMeters > 1_500.0 || extraDistanceMeters > 1_500.0
}

enum class RouteAdjustmentKind {
    DETOUR,
    REJOIN,
}

class RoutePath(track: GpxTrack) {
    val points: List<TrackPoint> = track.points
    private val distances: List<Double>
    val totalDistanceMeters: Double

    init {
        var distance = 0.0
        distances = points.mapIndexed { index, point ->
            if (index > 0) distance += GeoMath.distanceMeters(points[index - 1], point)
            distance
        }
        totalDistanceMeters = distance
    }

    fun pointAt(distanceMeters: Double): TrackPoint {
        require(points.isNotEmpty()) {
            localizedSystemText("Route contains no points.", "Route enthält keine Punkte.")
        }
        val target = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        val endIndex = distances.indexOfFirst { it >= target }.takeIf { it >= 0 } ?: points.lastIndex
        if (endIndex == 0) return points.first()
        val startIndex = endIndex - 1
        val legLength = distances[endIndex] - distances[startIndex]
        if (legLength <= 0.0) return points[endIndex]
        val fraction = ((target - distances[startIndex]) / legLength).coerceIn(0.0, 1.0)
        return interpolate(points[startIndex], points[endIndex], fraction)
    }

    fun slice(fromDistanceMeters: Double, toDistanceMeters: Double): List<TrackPoint> {
        if (points.isEmpty()) return emptyList()
        val from = fromDistanceMeters.coerceIn(0.0, totalDistanceMeters)
        val to = toDistanceMeters.coerceIn(from, totalDistanceMeters)
        return buildList {
            add(pointAt(from))
            points.forEachIndexed { index, point ->
                if (distances[index] > from && distances[index] < to) add(point)
            }
            val end = pointAt(to)
            if (lastOrNull() != end) add(end)
        }
    }

    fun nearestDistanceAlongRoute(
        position: TrackPoint,
        minimumDistanceMeters: Double = 0.0,
        maximumDistanceMeters: Double = totalDistanceMeters,
    ): Double? {
        if (points.size < 2) return null
        var best: Projection? = null
        points.zipWithNext().forEachIndexed { index, (start, end) ->
            val legStart = distances[index]
            val legEnd = distances[index + 1]
            if (legEnd < minimumDistanceMeters || legStart > maximumDistanceMeters) return@forEachIndexed
            val projection = project(position, start, end)
            val along = (legStart + (legEnd - legStart) * projection.fraction)
                .coerceIn(minimumDistanceMeters, maximumDistanceMeters)
            val candidate = Projection(along, projection.distanceMeters)
            if (best == null || candidate.distanceMeters < best!!.distanceMeters) best = candidate
        }
        return best?.distanceAlongMeters
    }

    private fun interpolate(start: TrackPoint, end: TrackPoint, fraction: Double) = TrackPoint(
        latitude = start.latitude + (end.latitude - start.latitude) * fraction,
        longitude = start.longitude + (end.longitude - start.longitude) * fraction,
        elevationMeters = if (start.elevationMeters != null && end.elevationMeters != null) {
            start.elevationMeters + (end.elevationMeters - start.elevationMeters) * fraction
        } else {
            start.elevationMeters ?: end.elevationMeters
        },
    )

    private fun project(position: TrackPoint, start: TrackPoint, end: TrackPoint): LegProjection {
        val referenceLat = Math.toRadians(position.latitude)
        fun local(point: TrackPoint): Pair<Double, Double> {
            val x = Math.toRadians(point.longitude - position.longitude) * cos(referenceLat) * EARTH_RADIUS_METERS
            val y = Math.toRadians(point.latitude - position.latitude) * EARTH_RADIUS_METERS
            return x to y
        }
        val (startX, startY) = local(start)
        val (endX, endY) = local(end)
        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared <= 0.0) return LegProjection(0.0, sqrt(startX * startX + startY * startY))
        val fraction = (-(startX * deltaX + startY * deltaY) / lengthSquared).coerceIn(0.0, 1.0)
        val nearestX = startX + fraction * deltaX
        val nearestY = startY + fraction * deltaY
        return LegProjection(fraction, sqrt(nearestX * nearestX + nearestY * nearestY))
    }

    private data class Projection(val distanceAlongMeters: Double, val distanceMeters: Double)
    private data class LegProjection(val fraction: Double, val distanceMeters: Double)

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

object DetourPlanner {
    fun corridor(
        route: GpxTrack,
        progressDistanceMeters: Double,
        requestedLengthMeters: Double,
        widthMeters: Int = DEFAULT_WIDTH_METERS,
    ): DetourCorridor {
        val path = RoutePath(route)
        require(path.totalDistanceMeters > MIN_ROUTE_REMAINDER_METERS) {
            localizedSystemText(
                "A detour is not possible for this route.",
                "Für diese Route ist keine Umleitung möglich.",
            )
        }
        val start = (progressDistanceMeters + START_AHEAD_METERS)
            .coerceIn(0.0, max(0.0, path.totalDistanceMeters - MIN_ROUTE_REMAINDER_METERS))
        val end = (start + requestedLengthMeters.coerceAtLeast(MIN_CORRIDOR_LENGTH_METERS))
            .coerceAtMost(path.totalDistanceMeters - MIN_ROUTE_REMAINDER_METERS)
        require(end > start) {
            localizedSystemText(
                "The blocked section is beyond the route destination.",
                "Der Sperrbereich liegt hinter dem Routenziel.",
            )
        }
        val corridorPoints = path.slice(start, end)
        val sampledDistances = buildList {
            var distance = start
            while (distance < end) {
                add(distance)
                distance += NO_GO_SAMPLE_SPACING_METERS
            }
            add(end)
        }.distinct().take(MAX_NO_GO_POINTS)
        return DetourCorridor(
            startDistanceMeters = start,
            endDistanceMeters = end,
            widthMeters = widthMeters,
            points = corridorPoints,
            noGoPoints = sampledDistances.map { distance ->
                RoutingNoGoPoint(path.pointAt(distance), widthMeters)
            },
        )
    }

    fun rejoinDistances(route: GpxTrack, corridor: DetourCorridor): List<Double> {
        val total = RoutePath(route).totalDistanceMeters
        return (REJOIN_OFFSETS_METERS.map { corridor.endDistanceMeters + it } + total)
            .map { it.coerceAtMost(total) }
            .filter { it > corridor.endDistanceMeters + corridor.widthMeters }
            .distinct()
    }

    fun rejoinDistances(
        route: GpxTrack,
        currentPosition: TrackPoint,
        progressDistanceMeters: Double,
    ): List<Double> {
        val path = RoutePath(route)
        val progress = progressDistanceMeters.coerceIn(0.0, path.totalDistanceMeters)
        val advised = RouteRejoinAdvisor(route).advise(currentPosition, progress)?.distanceAlongRouteMeters
            ?: path.nearestDistanceAlongRoute(
                currentPosition,
                minimumDistanceMeters = (progress - REJOIN_BACKWARD_TOLERANCE_METERS).coerceAtLeast(0.0),
            )
            ?: return emptyList()
        val minimum = (progress - REJOIN_BACKWARD_TOLERANCE_METERS).coerceAtLeast(0.0)
        return (REJOIN_ACCESS_OFFSETS_METERS.map { advised + it } + path.totalDistanceMeters)
            .map { it.coerceIn(minimum, path.totalDistanceMeters) }
            .distinctBy { (it / REJOIN_DISTANCE_DEDUPLICATION_METERS).toInt() }
            .take(MAX_REJOIN_CANDIDATES)
    }

    fun combine(
        route: GpxTrack,
        currentProgressMeters: Double,
        corridor: DetourCorridor,
        detour: GpxTrack,
        rejoinDistanceMeters: Double,
        directToDestination: Boolean = false,
    ): DetourRouteCandidate {
        val path = RoutePath(route)
        val tail = if (directToDestination) emptyList() else path.slice(rejoinDistanceMeters, path.totalDistanceMeters)
        val detourSegments = detour.segments
            .map { it.distinctAdjacent() }
            .filter { it.size >= 2 }
        val combinedSegments = buildList {
            addAll(detourSegments)
            if (tail.size >= 2) add(tail.distinctAdjacent())
        }
        val track = GpxTrack(
            name = route.name,
            segments = combinedSegments,
            activityType = route.activityType,
        )
        val originalRemaining = (path.totalDistanceMeters - currentProgressMeters).coerceAtLeast(0.0)
        val combinedDistance = TrackAnalyzer.calculate(track).distanceMeters
        return DetourRouteCandidate(
            track = track,
            detourTrack = detour.copy(segments = detourSegments),
            departureDistanceMeters = currentProgressMeters,
            rejoinDistanceMeters = rejoinDistanceMeters,
            skippedRouteMeters = (rejoinDistanceMeters - corridor.endDistanceMeters).coerceAtLeast(0.0),
            extraDistanceMeters = combinedDistance - originalRemaining,
            directToDestination = directToDestination,
        )
    }

    fun combineRejoin(
        route: GpxTrack,
        currentProgressMeters: Double,
        connector: GpxTrack,
        rejoinDistanceMeters: Double,
    ): DetourRouteCandidate {
        val path = RoutePath(route)
        val progress = currentProgressMeters.coerceIn(0.0, path.totalDistanceMeters)
        val rejoin = rejoinDistanceMeters.coerceIn(
            (progress - REJOIN_BACKWARD_TOLERANCE_METERS).coerceAtLeast(0.0),
            path.totalDistanceMeters,
        )
        val connectorSegments = connector.segments
            .map { it.distinctAdjacent() }
            .filter { it.size >= 2 }
        val tail = path.slice(rejoin, path.totalDistanceMeters).distinctAdjacent()
        val combined = GpxTrack(
            name = route.name,
            segments = buildList {
                addAll(connectorSegments)
                if (tail.size >= 2) add(tail)
            },
            activityType = route.activityType,
        )
        val originalRemaining = (path.totalDistanceMeters - progress).coerceAtLeast(0.0)
        return DetourRouteCandidate(
            track = combined,
            detourTrack = connector.copy(segments = connectorSegments),
            departureDistanceMeters = progress,
            rejoinDistanceMeters = rejoin,
            skippedRouteMeters = (rejoin - progress).coerceAtLeast(0.0),
            extraDistanceMeters = TrackAnalyzer.calculate(combined).distanceMeters - originalRemaining,
            directToDestination = rejoin >= path.totalDistanceMeters - 1.0,
        )
    }

    fun originalRouteOutsideDetour(
        route: GpxTrack,
        departureDistanceMeters: Double,
        rejoinDistanceMeters: Double,
    ): GpxTrack {
        val path = RoutePath(route)
        val departure = departureDistanceMeters.coerceIn(0.0, path.totalDistanceMeters)
        val rejoin = rejoinDistanceMeters.coerceIn(departure, path.totalDistanceMeters)
        val segments = buildList {
            path.slice(0.0, departure).takeIf { it.size >= 2 }?.let(::add)
            path.slice(rejoin, path.totalDistanceMeters).takeIf { it.size >= 2 }?.let(::add)
        }
        return route.copy(segments = segments)
    }

    private fun List<TrackPoint>.distinctAdjacent(): List<TrackPoint> = buildList {
        this@distinctAdjacent.forEach { point ->
            if (lastOrNull()?.let { GeoMath.distanceMeters(it, point) < 1.0 } != true) add(point)
        }
    }

    const val DEFAULT_CORRIDOR_LENGTH_METERS = 200.0
    const val MIN_CORRIDOR_LENGTH_METERS = 80.0
    const val MAX_CORRIDOR_LENGTH_METERS = 1_000.0
    const val DEFAULT_WIDTH_METERS = 30
    private const val START_AHEAD_METERS = 60.0
    private const val MIN_ROUTE_REMAINDER_METERS = 80.0
    private const val NO_GO_SAMPLE_SPACING_METERS = 35.0
    private const val MAX_NO_GO_POINTS = 60
    private val REJOIN_OFFSETS_METERS = listOf(100.0, 250.0, 500.0, 1_000.0, 2_000.0, 3_500.0)
    private val REJOIN_ACCESS_OFFSETS_METERS = listOf(0.0, 200.0, 500.0)
    private const val REJOIN_BACKWARD_TOLERANCE_METERS = 25.0
    private const val REJOIN_DISTANCE_DEDUPLICATION_METERS = 40.0
    private const val MAX_REJOIN_CANDIDATES = 3
}
