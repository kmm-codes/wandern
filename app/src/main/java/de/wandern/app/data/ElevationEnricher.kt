package de.wandern.app.data

import de.wandern.app.localization.localizedSystemText
import de.wandern.app.model.ElevationSource
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max

fun interface ElevationProvider {
    fun elevations(points: List<TrackPoint>): List<Double>
}

class ElevationEnricher(
    private val provider: ElevationProvider = OpenMeteoElevationProvider(),
) {
    fun enrichIfMissing(track: GpxTrack): GpxTrack {
        if (track.points.isEmpty() || track.points.any { it.elevationMeters != null }) return track

        val plans = samplingPlans(track)
        val requestedPoints = plans.flatMap { plan -> plan.sampleIndices.map(plan.points::get) }
        if (requestedPoints.isEmpty()) return track
        val elevations = provider.elevations(requestedPoints)
        require(elevations.size == requestedPoints.size) {
            localizedSystemText(
                "Elevation service returned ${elevations.size} instead of ${requestedPoints.size} values.",
                "Höhenservice lieferte ${elevations.size} statt ${requestedPoints.size} Werte.",
            )
        }

        var elevationOffset = 0
        val enrichedSegments = plans.map { plan ->
            val sampleElevations = elevations.subList(
                elevationOffset,
                elevationOffset + plan.sampleIndices.size,
            )
            elevationOffset += plan.sampleIndices.size
            interpolateSegment(plan, sampleElevations)
        }
        return track.copy(
            segments = enrichedSegments,
            elevationSource = ElevationSource.OPEN_METEO_COPERNICUS_GLO_90,
        )
    }

    private fun samplingPlans(track: GpxTrack): List<SegmentPlan> {
        val distances = track.segments.map(::cumulativeDistances)
        val totalDistance = distances.sumOf { it.lastOrNull() ?: 0.0 }
        val sampleSpacing = max(MIN_SAMPLE_SPACING_METERS, totalDistance / MAX_SAMPLE_POINTS)
        return track.segments.mapIndexed { index, points ->
            val cumulative = distances[index]
            SegmentPlan(points, cumulative, sampleIndices(cumulative, sampleSpacing))
        }
    }

    private fun cumulativeDistances(points: List<TrackPoint>): List<Double> {
        if (points.isEmpty()) return emptyList()
        var distance = 0.0
        return points.mapIndexed { index, point ->
            if (index > 0) distance += GeoMath.distanceMeters(points[index - 1], point)
            distance
        }
    }

    private fun sampleIndices(distances: List<Double>, spacing: Double): List<Int> {
        if (distances.isEmpty()) return emptyList()
        val indices = mutableListOf(0)
        var lastDistance = distances.first()
        for (index in 1 until distances.lastIndex) {
            if (distances[index] - lastDistance >= spacing) {
                indices += index
                lastDistance = distances[index]
            }
        }
        if (distances.lastIndex != indices.last()) indices += distances.lastIndex
        return indices
    }

    private fun interpolateSegment(plan: SegmentPlan, sampleElevations: List<Double>): List<TrackPoint> {
        if (plan.points.isEmpty()) return emptyList()
        if (plan.sampleIndices.size == 1) {
            return plan.points.map { it.copy(elevationMeters = sampleElevations.first()) }
        }
        var upperSample = 1
        return plan.points.mapIndexed { pointIndex, point ->
            while (
                upperSample < plan.sampleIndices.lastIndex &&
                pointIndex > plan.sampleIndices[upperSample]
            ) {
                upperSample++
            }
            val lowerSample = upperSample - 1
            val lowerIndex = plan.sampleIndices[lowerSample]
            val upperIndex = plan.sampleIndices[upperSample]
            val lowerDistance = plan.distances[lowerIndex]
            val upperDistance = plan.distances[upperIndex]
            val fraction = if (upperDistance > lowerDistance) {
                ((plan.distances[pointIndex] - lowerDistance) / (upperDistance - lowerDistance))
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val elevation = sampleElevations[lowerSample] +
                (sampleElevations[upperSample] - sampleElevations[lowerSample]) * fraction
            point.copy(elevationMeters = elevation)
        }
    }

    private data class SegmentPlan(
        val points: List<TrackPoint>,
        val distances: List<Double>,
        val sampleIndices: List<Int>,
    )

    private companion object {
        const val MIN_SAMPLE_SPACING_METERS = 60.0
        const val MAX_SAMPLE_POINTS = 500.0
    }
}

private class OpenMeteoElevationProvider : ElevationProvider {
    override fun elevations(points: List<TrackPoint>): List<Double> =
        points.chunked(MAX_POINTS_PER_REQUEST).flatMap(::fetchBatch)

    private fun fetchBatch(points: List<TrackPoint>): List<Double> {
        val latitudes = points.joinToString(",") { formatCoordinate(it.latitude) }
        val longitudes = points.joinToString(",") { formatCoordinate(it.longitude) }
        val connection = URL(
            "$ENDPOINT?latitude=$latitudes&longitude=$longitudes",
        ).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Wandern-Android/0.1")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val reason = connection.errorStream?.bufferedReader()?.use { it.readText() }
                error(
                    localizedSystemText(
                        "Elevation service returned HTTP $responseCode${reason?.let { ": $it" }.orEmpty()}",
                        "Höhenservice antwortete mit HTTP $responseCode${reason?.let { ": $it" }.orEmpty()}",
                    ),
                )
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val values = JSONObject(body).getJSONArray("elevation")
            List(values.length()) { index -> values.getDouble(index) }
        } finally {
            connection.disconnect()
        }
    }

    private fun formatCoordinate(value: Double) = String.format(Locale.US, "%.6f", value)

    private companion object {
        const val ENDPOINT = "https://api.open-meteo.com/v1/elevation"
        const val MAX_POINTS_PER_REQUEST = 100
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
