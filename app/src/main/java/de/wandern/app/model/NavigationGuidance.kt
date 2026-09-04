package de.wandern.app.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class NavigationManeuverType {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN,
    ARRIVE,
}

data class NavigationManeuver(
    val type: NavigationManeuverType,
    val point: TrackPoint,
    val distanceAlongRouteMeters: Double,
    val turnAngleDegrees: Double? = null,
)

enum class NavigationAnnouncementStage {
    APPROACH,
    NOW,
    ARRIVED,
}

data class NavigationGuidance(
    val maneuver: NavigationManeuver,
    val distanceMeters: Double,
    val announcement: NavigationAnnouncementStage? = null,
)

/**
 * Derives conservative turn hints from route geometry. Provider-supplied maneuvers remain
 * preferable, but this keeps imported and older GPX routes navigable without changing their path.
 */
object NavigationManeuverGenerator {
    fun generate(track: GpxTrack): List<NavigationManeuver> {
        val generated = mutableListOf<NavigationManeuver>()
        var globalOffsetMeters = 0.0
        track.segments.filter { it.size >= 2 }.forEach { segment ->
            val distances = cumulativeDistances(segment)
            val segmentLength = distances.last()
            val candidates = mutableListOf<NavigationManeuver>()
            for (index in 1 until segment.lastIndex) {
                val distance = distances[index]
                if (distance < END_GUARD_METERS || segmentLength - distance < END_GUARD_METERS) continue
                val beforeIndex = indexBefore(distances, index, LOOK_DISTANCE_METERS)
                val afterIndex = indexAfter(distances, index, LOOK_DISTANCE_METERS)
                if (beforeIndex == index || afterIndex == index) continue
                val incoming = bearingDegrees(segment[beforeIndex], segment[index])
                val outgoing = bearingDegrees(segment[index], segment[afterIndex])
                val turnAngle = signedAngle(outgoing - incoming)
                val type = typeForAngle(turnAngle) ?: continue
                candidates += NavigationManeuver(
                    type = type,
                    point = segment[index],
                    distanceAlongRouteMeters = globalOffsetMeters + distance,
                    turnAngleDegrees = turnAngle,
                )
            }
            generated += mergeNearby(candidates)
            globalOffsetMeters += segmentLength
        }
        val destination = track.segments.lastOrNull { it.isNotEmpty() }?.lastOrNull()
        if (destination != null && globalOffsetMeters > 0.0) {
            generated += NavigationManeuver(
                type = NavigationManeuverType.ARRIVE,
                point = destination,
                distanceAlongRouteMeters = globalOffsetMeters,
            )
        }
        return generated.sortedBy { it.distanceAlongRouteMeters }
    }

    fun withGeneratedManeuvers(track: GpxTrack): GpxTrack = if (track.navigationManeuvers.isNotEmpty()) {
        track
    } else {
        track.copy(navigationManeuvers = generate(track))
    }

    private fun cumulativeDistances(points: List<TrackPoint>): List<Double> {
        var total = 0.0
        return points.mapIndexed { index, point ->
            if (index > 0) total += GeoMath.distanceMeters(points[index - 1], point)
            total
        }
    }

    private fun indexBefore(distances: List<Double>, center: Int, lookDistance: Double): Int {
        val target = distances[center] - lookDistance
        for (index in center - 1 downTo 0) if (distances[index] <= target) return index
        return 0
    }

    private fun indexAfter(distances: List<Double>, center: Int, lookDistance: Double): Int {
        val target = distances[center] + lookDistance
        for (index in center + 1 until distances.size) if (distances[index] >= target) return index
        return distances.lastIndex
    }

    private fun mergeNearby(candidates: List<NavigationManeuver>): List<NavigationManeuver> {
        if (candidates.isEmpty()) return emptyList()
        val result = mutableListOf<NavigationManeuver>()
        var cluster = mutableListOf(candidates.first())
        candidates.drop(1).forEach { candidate ->
            if (candidate.distanceAlongRouteMeters - cluster.last().distanceAlongRouteMeters <= MERGE_DISTANCE_METERS) {
                cluster += candidate
            } else {
                result += strongest(cluster)
                cluster = mutableListOf(candidate)
            }
        }
        result += strongest(cluster)
        return result
    }

    private fun strongest(cluster: List<NavigationManeuver>): NavigationManeuver =
        cluster.maxBy { abs(it.turnAngleDegrees ?: 0.0) }

    private fun typeForAngle(angle: Double): NavigationManeuverType? = when {
        abs(angle) < MIN_TURN_ANGLE_DEGREES -> null
        abs(angle) >= U_TURN_ANGLE_DEGREES -> NavigationManeuverType.U_TURN
        angle >= SHARP_TURN_ANGLE_DEGREES -> NavigationManeuverType.SHARP_RIGHT
        angle >= TURN_ANGLE_DEGREES -> NavigationManeuverType.RIGHT
        angle > 0.0 -> NavigationManeuverType.SLIGHT_RIGHT
        angle <= -SHARP_TURN_ANGLE_DEGREES -> NavigationManeuverType.SHARP_LEFT
        angle <= -TURN_ANGLE_DEGREES -> NavigationManeuverType.LEFT
        else -> NavigationManeuverType.SLIGHT_LEFT
    }

    private fun bearingDegrees(from: TrackPoint, to: TrackPoint): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val y = sin(longitudeDelta) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(longitudeDelta)
        return Math.toDegrees(atan2(y, x)).mod(360.0)
    }

    private fun signedAngle(angle: Double): Double = (angle + 540.0).mod(360.0) - 180.0

    private const val LOOK_DISTANCE_METERS = 18.0
    private const val END_GUARD_METERS = 12.0
    private const val MERGE_DISTANCE_METERS = 28.0
    private const val MIN_TURN_ANGLE_DEGREES = 32.0
    private const val TURN_ANGLE_DEGREES = 58.0
    private const val SHARP_TURN_ANGLE_DEGREES = 120.0
    private const val U_TURN_ANGLE_DEGREES = 165.0
}

/** Tracks the next maneuver and emits each spoken stage at most once. */
class NavigationGuidanceTracker(track: GpxTrack) {
    private val maneuvers = NavigationManeuverGenerator.withGeneratedManeuvers(track).navigationManeuvers
    private var currentIndex = 0
    private var announcedApproachFor = -1
    private var announcedNowFor = -1
    private var announcedArrivalFor = -1

    fun update(distanceAlongRouteMeters: Double, suppress: Boolean = false): NavigationGuidance? {
        while (
            currentIndex < maneuvers.size &&
            distanceAlongRouteMeters > maneuvers[currentIndex].distanceAlongRouteMeters + PASSED_TOLERANCE_METERS
        ) {
            currentIndex += 1
        }
        val maneuver = maneuvers.getOrNull(currentIndex) ?: return null
        val remaining = (maneuver.distanceAlongRouteMeters - distanceAlongRouteMeters).coerceAtLeast(0.0)
        if (suppress) return null
        val announcement = when {
            maneuver.type == NavigationManeuverType.ARRIVE &&
                remaining <= ARRIVAL_ANNOUNCEMENT_METERS && announcedArrivalFor != currentIndex -> {
                announcedArrivalFor = currentIndex
                NavigationAnnouncementStage.ARRIVED
            }
            maneuver.type != NavigationManeuverType.ARRIVE &&
                remaining <= NOW_ANNOUNCEMENT_METERS && announcedNowFor != currentIndex -> {
                announcedNowFor = currentIndex
                NavigationAnnouncementStage.NOW
            }
            maneuver.type != NavigationManeuverType.ARRIVE &&
                remaining <= APPROACH_ANNOUNCEMENT_METERS && announcedApproachFor != currentIndex -> {
                announcedApproachFor = currentIndex
                NavigationAnnouncementStage.APPROACH
            }
            else -> null
        }
        return NavigationGuidance(maneuver, remaining, announcement)
    }

    companion object {
        /** Distance below which the hiker counts as standing at the destination. */
        const val ARRIVAL_ANNOUNCEMENT_METERS = 18.0

        private const val APPROACH_ANNOUNCEMENT_METERS = 110.0
        private const val NOW_ANNOUNCEMENT_METERS = 22.0
        private const val PASSED_TOLERANCE_METERS = 18.0
    }
}
