package de.wandern.app.model

import kotlin.math.cos
import kotlin.math.hypot

/** Reduces a dense GPX line to a small, shape-preserving set of editable route points. */
object RouteControlPointExtractor {
    fun extract(
        track: GpxTrack,
        maximumPoints: Int = 16,
        minimumDeviationMeters: Double = 30.0,
    ): List<TrackPoint> {
        require(maximumPoints >= 2)
        val points = track.points.filterAdjacentDuplicates()
        if (points.size <= 2) return points

        val selected = sortedSetOf(0, points.lastIndex)
        while (selected.size < maximumPoints) {
            var bestIndex = -1
            var bestDeviation = minimumDeviationMeters
            selected.zipWithNext().forEach { (startIndex, endIndex) ->
                for (index in startIndex + 1 until endIndex) {
                    val deviation = perpendicularDistanceMeters(
                        points[index],
                        points[startIndex],
                        points[endIndex],
                    )
                    if (deviation > bestDeviation) {
                        bestDeviation = deviation
                        bestIndex = index
                    }
                }
            }
            if (bestIndex < 0) break
            selected += bestIndex
        }
        return selected.map(points::get)
    }

    private fun List<TrackPoint>.filterAdjacentDuplicates(): List<TrackPoint> = buildList {
        this@filterAdjacentDuplicates.forEach { point ->
            val previous = lastOrNull()
            if (previous == null || previous.latitude != point.latitude || previous.longitude != point.longitude) {
                add(point)
            }
        }
    }

    private fun perpendicularDistanceMeters(
        point: TrackPoint,
        lineStart: TrackPoint,
        lineEnd: TrackPoint,
    ): Double {
        val referenceLatitude = Math.toRadians((lineStart.latitude + lineEnd.latitude) / 2.0)
        fun x(longitude: Double) = Math.toRadians(longitude - lineStart.longitude) *
            cos(referenceLatitude) * EARTH_RADIUS_METERS
        fun y(latitude: Double) = Math.toRadians(latitude - lineStart.latitude) * EARTH_RADIUS_METERS

        val endX = x(lineEnd.longitude)
        val endY = y(lineEnd.latitude)
        val pointX = x(point.longitude)
        val pointY = y(point.latitude)
        val lengthSquared = endX * endX + endY * endY
        if (lengthSquared == 0.0) return hypot(pointX, pointY)
        val projection = ((pointX * endX + pointY * endY) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(pointX - projection * endX, pointY - projection * endY)
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
