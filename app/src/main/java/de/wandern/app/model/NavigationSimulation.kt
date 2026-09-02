package de.wandern.app.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

data class SimulatedLocationSample(
    val point: TrackPoint,
    val elapsedMillis: Long,
)

enum class SimulationTurnDirection {
    LEFT,
    RIGHT,
}

/**
 * Deterministic geometry for debug navigation journeys.
 *
 * The caller describes movement in domain terms. This class deliberately emits location samples
 * instead of mutating a recording, so the production tracking pipeline still owns filtering,
 * distance, navigation guidance, deviation detection and persistence.
 */
object NavigationSimulation {
    fun followRoute(
        route: GpxTrack,
        fromDistanceMeters: Double,
        distanceMeters: Double,
        speedKilometersPerHour: Double,
        stepMeters: Double = DEFAULT_STEP_METERS,
    ): List<SimulatedLocationSample> {
        val path = RoutePath(route)
        require(path.totalDistanceMeters > 0.0) { "Route must contain a usable line." }
        require(distanceMeters > 0.0) { "Distance must be positive." }
        val from = fromDistanceMeters.coerceIn(0.0, path.totalDistanceMeters)
        val to = (from + distanceMeters).coerceAtMost(path.totalDistanceMeters)
        return samplesAlong(
            points = spacedRoutePoints(path, from, to, stepMeters),
            speedKilometersPerHour = speedKilometersPerHour,
        )
    }

    fun deviateAtNextTurn(
        route: GpxTrack,
        fromDistanceMeters: Double,
        direction: SimulationTurnDirection,
        deviationDistanceMeters: Double,
        speedKilometersPerHour: Double,
        stepMeters: Double = DEFAULT_STEP_METERS,
    ): List<SimulatedLocationSample> {
        val path = RoutePath(route)
        require(path.totalDistanceMeters > 0.0) { "Route must contain a usable line." }
        require(deviationDistanceMeters > 0.0) { "Deviation distance must be positive." }
        val from = fromDistanceMeters.coerceIn(0.0, path.totalDistanceMeters)
        val departureDistance = route.navigationManeuvers
            .asSequence()
            .map(NavigationManeuver::distanceAlongRouteMeters)
            .firstOrNull {
                it >= from + MIN_NEXT_TURN_DISTANCE_METERS &&
                    it <= from + MAX_NEXT_TURN_AHEAD_METERS
            }
            ?.coerceAtMost(path.totalDistanceMeters)
            ?: (from + DEFAULT_DEPARTURE_AHEAD_METERS).coerceAtMost(path.totalDistanceMeters)
        val routePoints = spacedRoutePoints(path, from, departureDistance, stepMeters)
        val headingStart = path.pointAt((departureDistance - HEADING_SAMPLE_METERS).coerceAtLeast(0.0))
        val departure = path.pointAt(departureDistance)
        val routeBearing = bearingDegrees(headingStart, departure)
        val deviationBearing = (routeBearing + if (direction == SimulationTurnDirection.RIGHT) 90.0 else -90.0)
            .mod(360.0)
        val deviationPoints = spacedDirectPoints(
            start = departure,
            bearingDegrees = deviationBearing,
            distanceMeters = deviationDistanceMeters,
            stepMeters = stepMeters,
        )
        return samplesAlong(
            points = (routePoints + deviationPoints.drop(1)).distinctConsecutive(),
            speedKilometersPerHour = speedKilometersPerHour,
        )
    }

    fun rejoinRoute(
        route: GpxTrack,
        currentPosition: TrackPoint,
        progressAnchorMeters: Double,
        speedKilometersPerHour: Double,
        stepMeters: Double = DEFAULT_STEP_METERS,
    ): List<SimulatedLocationSample> {
        val guidance = RouteRejoinAdvisor(route).advise(currentPosition, progressAnchorMeters)
            ?: error("No route re-entry is available.")
        val distance = GeoMath.distanceMeters(currentPosition, guidance.target)
        if (distance <= 0.5) {
            return listOf(SimulatedLocationSample(currentPosition, 0L))
        }
        return samplesAlong(
            points = spacedDirectPoints(
                start = currentPosition,
                end = guidance.target,
                distanceMeters = distance,
                stepMeters = stepMeters,
            ),
            speedKilometersPerHour = speedKilometersPerHour,
        )
    }

    fun routeProgress(route: GpxTrack, recordedTrack: GpxTrack): Double {
        val latest = recordedTrack.points.lastOrNull() ?: return 0.0
        return RoutePath(route).nearestDistanceAlongRoute(latest) ?: 0.0
    }

    private fun spacedRoutePoints(
        path: RoutePath,
        fromDistanceMeters: Double,
        toDistanceMeters: Double,
        stepMeters: Double,
    ): List<TrackPoint> {
        val distance = (toDistanceMeters - fromDistanceMeters).coerceAtLeast(0.0)
        if (distance <= 0.0) return listOf(path.pointAt(fromDistanceMeters))
        val steps = ceil(distance / stepMeters.coerceAtLeast(1.0)).toInt().coerceAtLeast(1)
        return (0..steps).map { index ->
            path.pointAt(fromDistanceMeters + distance * index / steps)
        }
    }

    private fun spacedDirectPoints(
        start: TrackPoint,
        bearingDegrees: Double,
        distanceMeters: Double,
        stepMeters: Double,
    ): List<TrackPoint> {
        val end = destination(start, bearingDegrees, distanceMeters)
        return spacedDirectPoints(start, end, distanceMeters, stepMeters)
    }

    private fun spacedDirectPoints(
        start: TrackPoint,
        end: TrackPoint,
        distanceMeters: Double,
        stepMeters: Double,
    ): List<TrackPoint> {
        val steps = ceil(distanceMeters / stepMeters.coerceAtLeast(1.0)).toInt().coerceAtLeast(1)
        return (0..steps).map { index ->
            val fraction = index.toDouble() / steps
            TrackPoint(
                latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                elevationMeters = interpolate(start.elevationMeters, end.elevationMeters, fraction),
            )
        }
    }

    private fun samplesAlong(
        points: List<TrackPoint>,
        speedKilometersPerHour: Double,
    ): List<SimulatedLocationSample> {
        require(speedKilometersPerHour > 0.0) { "Speed must be positive." }
        val speedMetersPerSecond = speedKilometersPerHour / 3.6
        var elapsedMillis = 0L
        return points.mapIndexed { index, point ->
            val previous = points.getOrNull(index - 1)
            if (previous != null) {
                elapsedMillis += (GeoMath.distanceMeters(previous, point) / speedMetersPerSecond * 1_000.0)
                    .toLong()
                    .coerceAtLeast(1L)
            }
            val next = points.getOrNull(index + 1) ?: previous
            SimulatedLocationSample(
                point = point.copy(
                    accuracyMeters = DEFAULT_ACCURACY_METERS,
                    speedMetersPerSecond = speedMetersPerSecond.toFloat(),
                    bearingDegrees = next?.let { bearingDegrees(point, it).toFloat() },
                ),
                elapsedMillis = elapsedMillis,
            )
        }
    }

    private fun destination(start: TrackPoint, bearingDegrees: Double, distanceMeters: Double): TrackPoint {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val latitude = Math.toRadians(start.latitude)
        val longitude = Math.toRadians(start.longitude)
        val targetLatitude = kotlin.math.asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val targetLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(targetLatitude),
        )
        return TrackPoint(
            latitude = Math.toDegrees(targetLatitude),
            longitude = Math.toDegrees(targetLongitude),
            elevationMeters = start.elevationMeters,
        )
    }

    private fun bearingDegrees(from: TrackPoint, to: TrackPoint): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLongitude) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLongitude)
        return Math.toDegrees(atan2(y, x)).mod(360.0)
    }

    private fun interpolate(start: Double?, end: Double?, fraction: Double): Double? = when {
        start != null && end != null -> start + (end - start) * fraction
        start != null -> start
        else -> end
    }

    private fun List<TrackPoint>.distinctConsecutive(): List<TrackPoint> = filterIndexed { index, point ->
        index == 0 || GeoMath.distanceMeters(this[index - 1], point) > 0.05
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val DEFAULT_STEP_METERS = 10.0
    private const val DEFAULT_DEPARTURE_AHEAD_METERS = 100.0
    private const val MIN_NEXT_TURN_DISTANCE_METERS = 20.0
    private const val MAX_NEXT_TURN_AHEAD_METERS = 250.0
    private const val HEADING_SAMPLE_METERS = 15.0
    private const val DEFAULT_ACCURACY_METERS = 6f
}
