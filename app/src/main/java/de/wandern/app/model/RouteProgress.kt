package de.wandern.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RouteProgress(
    val distanceAlongRouteMeters: Double,
    val totalDistanceMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingAscentMeters: Double,
    val remainingDescentMeters: Double,
    val distanceFromRouteMeters: Double?,
    val remainingElevationProfile: List<ProfileSample>,
) {
    val fraction: Double = if (totalDistanceMeters > 0.0) {
        (distanceAlongRouteMeters / totalDistanceMeters).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
}

class RouteProgressCalculator(track: GpxTrack) {
    private val segments: List<PreparedSegment>
    private val totalDistanceMeters: Double

    init {
        var globalDistance = 0.0
        segments = track.segments.filter { it.isNotEmpty() }.map { points ->
            val distances = cumulativeDistances(points)
            PreparedSegment(
                startDistanceMeters = globalDistance,
                points = points,
                distances = distances,
                elevations = smoothedElevations(points),
            ).also { globalDistance += distances.lastOrNull() ?: 0.0 }
        }
        totalDistanceMeters = globalDistance
    }

    fun initial(): RouteProgress? {
        if (totalDistanceMeters <= 0.0) return null
        return progressAt(
            segmentIndex = 0,
            legIndex = 0,
            fractionOnLeg = 0.0,
            distanceAlongRouteMeters = 0.0,
            distanceFromRouteMeters = null,
        )
    }

    fun calculate(position: TrackPoint, previousDistanceMeters: Double? = null): RouteProgress? {
        if (totalDistanceMeters <= 0.0) return null
        val candidates = buildList {
            segments.forEachIndexed { segmentIndex, segment ->
                segment.points.zipWithNext().forEachIndexed { legIndex, (start, end) ->
                    val projection = project(position, start, end)
                    val legLength = segment.distances[legIndex + 1] - segment.distances[legIndex]
                    add(
                        Candidate(
                            segmentIndex = segmentIndex,
                            legIndex = legIndex,
                            fractionOnLeg = projection.fraction,
                            distanceFromRouteMeters = projection.distanceMeters,
                            distanceAlongRouteMeters = segment.startDistanceMeters +
                                segment.distances[legIndex] + legLength * projection.fraction,
                        ),
                    )
                }
            }
        }
        if (candidates.isEmpty()) return null
        val nearestDistance = candidates.minOf { it.distanceFromRouteMeters }
        val ambiguityRadius = max(MIN_AMBIGUITY_RADIUS_METERS, position.accuracyMeters?.toDouble() ?: 0.0)
        val nearbyCandidates = candidates.filter {
            it.distanceFromRouteMeters <= nearestDistance + ambiguityRadius
        }
        val selected = if (previousDistanceMeters == null) {
            nearbyCandidates.minWithOrNull(
                compareBy<Candidate> { it.distanceFromRouteMeters }
                    .thenBy { it.distanceAlongRouteMeters },
            )
        } else {
            nearbyCandidates.minWithOrNull(
                compareBy<Candidate> { abs(it.distanceAlongRouteMeters - previousDistanceMeters) }
                    .thenBy { it.distanceFromRouteMeters },
            )
        } ?: return null
        return progressAt(
            segmentIndex = selected.segmentIndex,
            legIndex = selected.legIndex,
            fractionOnLeg = selected.fractionOnLeg,
            distanceAlongRouteMeters = selected.distanceAlongRouteMeters,
            distanceFromRouteMeters = selected.distanceFromRouteMeters,
        )
    }

    private fun progressAt(
        segmentIndex: Int,
        legIndex: Int,
        fractionOnLeg: Double,
        distanceAlongRouteMeters: Double,
        distanceFromRouteMeters: Double?,
    ): RouteProgress {
        val remainingProfiles = mutableListOf<ProfileSample>()
        val currentSegment = segments[segmentIndex]
        interpolateElevation(currentSegment, legIndex, fractionOnLeg)?.let {
            remainingProfiles += ProfileSample(0.0, it)
        }
        for (index in legIndex + 1 until currentSegment.points.size) {
            currentSegment.elevations[index]?.let { elevation ->
                val globalDistance = currentSegment.startDistanceMeters + currentSegment.distances[index]
                remainingProfiles += ProfileSample(globalDistance - distanceAlongRouteMeters, elevation)
            }
        }
        for (index in segmentIndex + 1 until segments.size) {
            val segment = segments[index]
            segment.elevations.forEachIndexed { pointIndex, elevation ->
                elevation?.let {
                    val globalDistance = segment.startDistanceMeters + segment.distances[pointIndex]
                    remainingProfiles += ProfileSample(globalDistance - distanceAlongRouteMeters, it)
                }
            }
        }
        val (remainingAscent, remainingDescent) = remainingElevationGain(
            segmentIndex,
            legIndex,
            fractionOnLeg,
        )
        return RouteProgress(
            distanceAlongRouteMeters = distanceAlongRouteMeters.coerceIn(0.0, totalDistanceMeters),
            totalDistanceMeters = totalDistanceMeters,
            remainingDistanceMeters = (totalDistanceMeters - distanceAlongRouteMeters).coerceAtLeast(0.0),
            remainingAscentMeters = remainingAscent,
            remainingDescentMeters = remainingDescent,
            distanceFromRouteMeters = distanceFromRouteMeters,
            remainingElevationProfile = remainingProfiles,
        )
    }

    private fun remainingElevationGain(
        segmentIndex: Int,
        legIndex: Int,
        fractionOnLeg: Double,
    ): Pair<Double, Double> {
        var ascent = 0.0
        var descent = 0.0
        fun addSegment(elevations: List<Double?>) {
            var reference = elevations.firstOrNull { it != null } ?: return
            elevations.dropWhile { it == null }.drop(1).forEach { elevation ->
                val current = elevation ?: return@forEach
                val difference = current - reference
                if (abs(difference) >= MIN_ELEVATION_CHANGE_METERS) {
                    if (difference > 0.0) ascent += difference else descent -= difference
                    reference = current
                }
            }
        }

        val currentSegment = segments[segmentIndex]
        val currentElevations = buildList {
            add(interpolateElevation(currentSegment, legIndex, fractionOnLeg))
            for (index in legIndex + 1 until currentSegment.elevations.size) {
                add(currentSegment.elevations[index])
            }
        }
        addSegment(currentElevations)
        for (index in segmentIndex + 1 until segments.size) addSegment(segments[index].elevations)
        return ascent to descent
    }

    private fun interpolateElevation(segment: PreparedSegment, legIndex: Int, fraction: Double): Double? {
        val start = segment.elevations.getOrNull(legIndex) ?: return null
        val end = segment.elevations.getOrNull(legIndex + 1) ?: return null
        return start + (end - start) * fraction
    }

    private fun cumulativeDistances(points: List<TrackPoint>): List<Double> {
        var distance = 0.0
        return points.mapIndexed { index, point ->
            if (index > 0) distance += GeoMath.distanceMeters(points[index - 1], point)
            distance
        }
    }

    private fun smoothedElevations(points: List<TrackPoint>): List<Double?> =
        points.mapIndexed { index, point ->
            if (point.elevationMeters == null) return@mapIndexed null
            points.subList(max(0, index - 2), min(points.size, index + 3))
                .mapNotNull { it.elevationMeters }
                .takeIf { it.isNotEmpty() }
                ?.average()
        }

    private fun project(position: TrackPoint, start: TrackPoint, end: TrackPoint): Projection {
        val referenceLat = position.latitude.toRadians()
        fun local(point: TrackPoint): Pair<Double, Double> {
            val x = (point.longitude - position.longitude).toRadians() * cos(referenceLat) * EARTH_RADIUS_METERS
            val y = (point.latitude - position.latitude).toRadians() * EARTH_RADIUS_METERS
            return x to y
        }
        val (startX, startY) = local(start)
        val (endX, endY) = local(end)
        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        if (lengthSquared == 0.0) return Projection(0.0, sqrt(startX * startX + startY * startY))
        val fraction = (-(startX * deltaX + startY * deltaY) / lengthSquared).coerceIn(0.0, 1.0)
        val nearestX = startX + fraction * deltaX
        val nearestY = startY + fraction * deltaY
        return Projection(fraction, sqrt(nearestX * nearestX + nearestY * nearestY))
    }

    private data class PreparedSegment(
        val startDistanceMeters: Double,
        val points: List<TrackPoint>,
        val distances: List<Double>,
        val elevations: List<Double?>,
    )

    private data class Candidate(
        val segmentIndex: Int,
        val legIndex: Int,
        val fractionOnLeg: Double,
        val distanceFromRouteMeters: Double,
        val distanceAlongRouteMeters: Double,
    )

    private data class Projection(val fraction: Double, val distanceMeters: Double)

    private fun Double.toRadians() = this * PI / 180.0

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MIN_AMBIGUITY_RADIUS_METERS = 15.0
        const val MIN_ELEVATION_CHANGE_METERS = 1.5
    }
}

class RouteProgressTracker(track: GpxTrack) {
    private val calculator = RouteProgressCalculator(track)
    private var accepted: RouteProgress? = null
    private var lastObservationTimeMillis: Long? = null
    private var pending: RouteProgress? = null
    private var pendingConfirmations = 0

    fun currentOrInitial(): RouteProgress? = accepted ?: calculator.initial()

    fun update(position: TrackPoint): RouteProgress? {
        val candidate = calculator.calculate(position, accepted?.distanceAlongRouteMeters) ?: return currentOrInitial()
        val observationTime = position.timeMillis ?: System.currentTimeMillis()
        val elapsedSeconds = lastObservationTimeMillis?.let {
            ((observationTime - it).coerceAtLeast(0L) / 1_000.0)
        } ?: 0.0
        lastObservationTimeMillis = observationTime
        if (candidate.distanceFromRouteMeters != null &&
            candidate.distanceFromRouteMeters > MAX_PROGRESS_CORRIDOR_METERS
        ) {
            pending = null
            pendingConfirmations = 0
            return currentOrInitial()
        }
        val previous = accepted
        if (previous == null) return accept(candidate)
        val allowedJump = max(
            MIN_ALLOWED_JUMP_METERS,
            elapsedSeconds * MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND +
                (position.accuracyMeters?.toDouble() ?: 0.0) * 2.0,
        )
        if (abs(candidate.distanceAlongRouteMeters - previous.distanceAlongRouteMeters) <= allowedJump) {
            return accept(candidate)
        }
        val existingPending = pending
        if (existingPending != null &&
            abs(existingPending.distanceAlongRouteMeters - candidate.distanceAlongRouteMeters) <=
            PENDING_MATCH_DISTANCE_METERS
        ) {
            pendingConfirmations++
        } else {
            pending = candidate
            pendingConfirmations = 1
        }
        return if (pendingConfirmations >= REQUIRED_JUMP_CONFIRMATIONS) accept(candidate) else previous
    }

    private fun accept(progress: RouteProgress): RouteProgress {
        accepted = progress
        pending = null
        pendingConfirmations = 0
        return progress
    }

    private companion object {
        const val MAX_PROGRESS_CORRIDOR_METERS = 120.0
        const val MIN_ALLOWED_JUMP_METERS = 100.0
        const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 4.0
        const val PENDING_MATCH_DISTANCE_METERS = 100.0
        const val REQUIRED_JUMP_CONFIRMATIONS = 2
    }
}
