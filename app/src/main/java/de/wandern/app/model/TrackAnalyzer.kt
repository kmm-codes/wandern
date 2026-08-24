package de.wandern.app.model

import kotlin.math.abs

object TrackAnalyzer {
    private const val MAX_INTERVAL_MILLIS = 120_000L
    private const val MOVING_SPEED_THRESHOLD_MPS = 0.35
    private const val MIN_ELEVATION_CHANGE_METERS = 1.5
    private const val SLOPE_WINDOW_METERS = 40.0

    fun calculate(track: GpxTrack): TrackStats {
        var totalDistance = 0.0
        var totalDuration = 0L
        var movingDuration = 0L

        track.segments.forEach { segment ->
            segment.zipWithNext().forEach { (previous, current) ->
                val distance = GeoMath.distanceMeters(previous, current)
                totalDistance += distance
                val interval = intervalMillis(previous, current)
                if (interval != null && interval <= MAX_INTERVAL_MILLIS) {
                    totalDuration += interval
                    val speed = if (interval > 0) distance / (interval / 1000.0) else 0.0
                    if (speed >= MOVING_SPEED_THRESHOLD_MPS) movingDuration += interval
                }
            }
        }

        val (ascent, descent) = elevationGain(track)
        val denominator = movingDuration.takeIf { it > 0 } ?: totalDuration
        val averageSpeed = if (denominator > 0) totalDistance / (denominator / 1000.0) else 0.0
        val pace = averageSpeed.takeIf { it > 0 }?.let { 1000.0 / it }

        return TrackStats(
            distanceMeters = totalDistance,
            durationMillis = totalDuration,
            movingDurationMillis = movingDuration,
            ascentMeters = ascent,
            descentMeters = descent,
            averageSpeedMetersPerSecond = averageSpeed,
            paceSecondsPerKilometer = pace,
            currentSlopePercent = currentSlope(track),
            pointCount = track.points.size,
        )
    }

    private fun intervalMillis(previous: TrackPoint, current: TrackPoint): Long? {
        val previousTime = previous.timeMillis ?: return null
        val currentTime = current.timeMillis ?: return null
        return (currentTime - previousTime).takeIf { it >= 0 }
    }

    private fun elevationGain(track: GpxTrack): Pair<Double, Double> {
        var ascent = 0.0
        var descent = 0.0
        track.segments.forEach { segment ->
            val smoothed = segment.mapIndexedNotNull { index, point ->
                if (point.elevationMeters == null) return@mapIndexedNotNull null
                val window = segment.subList(maxOf(0, index - 2), minOf(segment.size, index + 3))
                    .mapNotNull { it.elevationMeters }
                window.average().takeIf { window.isNotEmpty() }
            }
            var reference = smoothed.firstOrNull() ?: return@forEach
            smoothed.drop(1).forEach { current ->
                val difference = current - reference
                if (abs(difference) >= MIN_ELEVATION_CHANGE_METERS) {
                    if (difference > 0) ascent += difference else descent -= difference
                    reference = current
                }
            }
        }
        return ascent to descent
    }

    private fun currentSlope(track: GpxTrack): Double? {
        val points = track.segments.lastOrNull().orEmpty()
        val end = points.lastOrNull() ?: return null
        val endElevation = end.elevationMeters ?: return null
        var distance = 0.0
        var index = points.lastIndex
        while (index > 0 && distance < SLOPE_WINDOW_METERS) {
            distance += GeoMath.distanceMeters(points[index - 1], points[index])
            index--
        }
        if (distance < 10.0) return null
        val startElevation = points[index].elevationMeters ?: return null
        return ((endElevation - startElevation) / distance * 100.0).coerceIn(-100.0, 100.0)
    }
}
