package de.wandern.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteRejoinGuidance(
    val target: TrackPoint,
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val distanceAlongRouteMeters: Double,
)

/**
 * Selects a nearby, progress-aware point on a route without requiring a routing graph.
 * The result is intentionally a direction and an air-line distance, not a claim that the
 * straight line is walkable.
 */
class RouteRejoinAdvisor(track: GpxTrack) {
    private val legs: List<Leg>

    init {
        var distanceBeforeSegment = 0.0
        legs = buildList {
            track.segments.filter { it.isNotEmpty() }.forEach { segment ->
                segment.zipWithNext().forEach { (start, end) ->
                    val length = GeoMath.distanceMeters(start, end)
                    add(Leg(start, end, distanceBeforeSegment, length))
                    distanceBeforeSegment += length
                }
            }
        }
    }

    fun advise(position: TrackPoint, progressAnchorMeters: Double? = null): RouteRejoinGuidance? {
        if (legs.isEmpty()) return null
        val candidates = legs.map { leg ->
            val projection = project(position, leg.start, leg.end)
            Candidate(
                target = projection.point,
                directDistanceMeters = projection.distanceMeters,
                distanceAlongRouteMeters = leg.distanceBeforeMeters + leg.lengthMeters * projection.fraction,
            )
        }
        val candidate = if (progressAnchorMeters == null) {
            candidates.minByOrNull { it.directDistanceMeters }
        } else {
            candidates.minByOrNull { it.score(progressAnchorMeters) }
        } ?: return null
        return RouteRejoinGuidance(
            target = candidate.target,
            distanceMeters = candidate.directDistanceMeters,
            bearingDegrees = bearingDegrees(position, candidate.target),
            distanceAlongRouteMeters = candidate.distanceAlongRouteMeters,
        )
    }

    private fun Candidate.score(anchorMeters: Double): Double {
        val progressDelta = distanceAlongRouteMeters - anchorMeters
        val progressPenalty = when {
            progressDelta < -BACKWARD_TOLERANCE_METERS ->
                abs(progressDelta + BACKWARD_TOLERANCE_METERS) * BACKWARD_PENALTY_PER_METER
            progressDelta > FREE_FORWARD_PROGRESS_METERS ->
                (progressDelta - FREE_FORWARD_PROGRESS_METERS) * FORWARD_SKIP_PENALTY_PER_METER
            else -> max(0.0, progressDelta) * NEARBY_FORWARD_PENALTY_PER_METER
        }
        return directDistanceMeters + progressPenalty
    }

    private fun project(position: TrackPoint, start: TrackPoint, end: TrackPoint): Projection {
        val referenceLat = position.latitude.toRadians()
        fun local(point: TrackPoint): Pair<Double, Double> {
            val x = (point.longitude - position.longitude).toRadians() *
                cos(referenceLat) * EARTH_RADIUS_METERS
            val y = (point.latitude - position.latitude).toRadians() * EARTH_RADIUS_METERS
            return x to y
        }
        val (startX, startY) = local(start)
        val (endX, endY) = local(end)
        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        val fraction = if (lengthSquared == 0.0) {
            0.0
        } else {
            (-(startX * deltaX + startY * deltaY) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val nearestX = startX + fraction * deltaX
        val nearestY = startY + fraction * deltaY
        return Projection(
            point = TrackPoint(
                latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                elevationMeters = interpolate(start.elevationMeters, end.elevationMeters, fraction),
            ),
            fraction = fraction,
            distanceMeters = sqrt(nearestX * nearestX + nearestY * nearestY),
        )
    }

    private fun interpolate(start: Double?, end: Double?, fraction: Double): Double? = when {
        start != null && end != null -> start + (end - start) * fraction
        start != null -> start
        else -> end
    }

    private fun bearingDegrees(from: TrackPoint, to: TrackPoint): Double {
        val fromLat = from.latitude.toRadians()
        val toLat = to.latitude.toRadians()
        val deltaLongitude = (to.longitude - from.longitude).toRadians()
        val y = sin(deltaLongitude) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLongitude)
        return Math.toDegrees(atan2(y, x)).mod(360.0)
    }

    private data class Leg(
        val start: TrackPoint,
        val end: TrackPoint,
        val distanceBeforeMeters: Double,
        val lengthMeters: Double,
    )

    private data class Candidate(
        val target: TrackPoint,
        val directDistanceMeters: Double,
        val distanceAlongRouteMeters: Double,
    )

    private data class Projection(
        val point: TrackPoint,
        val fraction: Double,
        val distanceMeters: Double,
    )

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val BACKWARD_TOLERANCE_METERS = 25.0
        const val FREE_FORWARD_PROGRESS_METERS = 250.0
        const val BACKWARD_PENALTY_PER_METER = 4.0
        const val FORWARD_SKIP_PENALTY_PER_METER = 0.8
        const val NEARBY_FORWARD_PENALTY_PER_METER = 0.04
    }
}
