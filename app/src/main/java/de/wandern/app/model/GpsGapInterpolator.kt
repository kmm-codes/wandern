package de.wandern.app.model

import kotlin.math.max

object GpsGapInterpolator {
    fun between(
        start: TrackPoint,
        end: TrackPoint,
        intervalMillis: Long = 5_000L,
        maxPoints: Int = 120,
    ): List<TrackPoint> {
        require(intervalMillis > 0) { "Das Interpolationsintervall muss positiv sein." }
        require(maxPoints >= 0) { "Die maximale Punktzahl darf nicht negativ sein." }
        val startTime = start.timeMillis ?: return emptyList()
        val endTime = end.timeMillis ?: return emptyList()
        val duration = endTime - startTime
        if (duration <= intervalMillis || maxPoints == 0) return emptyList()

        val segmentCount = max(2, ((duration + intervalMillis - 1) / intervalMillis).toInt())
        val pointCount = (segmentCount - 1).coerceAtMost(maxPoints)
        val longitudeDelta = shortestLongitudeDelta(start.longitude, end.longitude)
        return (1..pointCount).map { index ->
            val fraction = index.toDouble() / (pointCount + 1).toDouble()
            val elevation = if (start.elevationMeters != null && end.elevationMeters != null) {
                start.elevationMeters + (end.elevationMeters - start.elevationMeters) * fraction
            } else {
                null
            }
            TrackPoint(
                latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                longitude = normalizeLongitude(start.longitude + longitudeDelta * fraction),
                elevationMeters = elevation,
                timeMillis = startTime + (duration * fraction).toLong(),
                accuracyMeters = maxOf(start.accuracyMeters ?: 0f, end.accuracyMeters ?: 0f)
                    .takeIf { it > 0f },
                speedMetersPerSecond = (GeoMath.distanceMeters(start, end) / (duration / 1000.0)).toFloat(),
                isInterpolated = true,
            )
        }
    }

    private fun shortestLongitudeDelta(start: Double, end: Double): Double =
        (end - start + 540.0) % 360.0 - 180.0

    private fun normalizeLongitude(longitude: Double): Double =
        (longitude + 540.0) % 360.0 - 180.0
}
