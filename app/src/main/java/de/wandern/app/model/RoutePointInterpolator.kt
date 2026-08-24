package de.wandern.app.model

object RoutePointInterpolator {
    fun pointAtDistance(track: GpxTrack, distanceMeters: Double): TrackPoint? {
        val nonEmptySegments = track.segments.filter { it.isNotEmpty() }
        val first = nonEmptySegments.firstOrNull()?.firstOrNull() ?: return null
        val targetDistance = distanceMeters.coerceAtLeast(0.0)
        if (targetDistance == 0.0) return first

        var coveredDistance = 0.0
        nonEmptySegments.forEach { segment ->
            segment.zipWithNext().forEach { (start, end) ->
                val legDistance = GeoMath.distanceMeters(start, end)
                if (legDistance > 0.0 && coveredDistance + legDistance >= targetDistance) {
                    val fraction = ((targetDistance - coveredDistance) / legDistance).coerceIn(0.0, 1.0)
                    return TrackPoint(
                        latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                        longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                        elevationMeters = interpolate(start.elevationMeters, end.elevationMeters, fraction),
                        timeMillis = interpolate(start.timeMillis, end.timeMillis, fraction),
                    )
                }
                coveredDistance += legDistance
            }
        }
        return nonEmptySegments.last().last()
    }

    private fun interpolate(start: Double?, end: Double?, fraction: Double): Double? =
        if (start != null && end != null) start + (end - start) * fraction else start ?: end

    private fun interpolate(start: Long?, end: Long?, fraction: Double): Long? =
        if (start != null && end != null) (start + (end - start) * fraction).toLong() else start ?: end
}
