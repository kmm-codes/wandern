package de.wandern.app.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
        val lat1 = a.latitude.toRadians()
        val lat2 = b.latitude.toRadians()
        val deltaLat = (b.latitude - a.latitude).toRadians()
        val deltaLon = (b.longitude - a.longitude).toRadians()
        val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    fun distanceToTrackMeters(point: TrackPoint, track: GpxTrack): Double? {
        val segments = track.segments.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        return segments.minOf { segment ->
            if (segment.size == 1) {
                distanceMeters(point, segment.first())
            } else {
                segment.zipWithNext().minOf { (start, end) ->
                    distanceToSegmentMeters(point, start, end)
                }
            }
        }
    }

    private fun distanceToSegmentMeters(point: TrackPoint, start: TrackPoint, end: TrackPoint): Double {
        val referenceLat = point.latitude.toRadians()
        fun project(p: TrackPoint): Pair<Double, Double> {
            val x = (p.longitude - point.longitude).toRadians() * cos(referenceLat) * EARTH_RADIUS_METERS
            val y = (p.latitude - point.latitude).toRadians() * EARTH_RADIUS_METERS
            return x to y
        }

        val (sx, sy) = project(start)
        val (ex, ey) = project(end)
        val dx = ex - sx
        val dy = ey - sy
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return sqrt(sx * sx + sy * sy)

        val projection = max(0.0, min(1.0, -(sx * dx + sy * dy) / lengthSquared))
        val nearestX = sx + projection * dx
        val nearestY = sy + projection * dy
        return sqrt(nearestX * nearestX + nearestY * nearestY)
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

