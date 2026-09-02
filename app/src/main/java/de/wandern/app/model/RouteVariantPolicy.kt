package de.wandern.app.model

import de.wandern.app.localization.localizedSystemText
import java.util.Locale

object RouteVariantPolicy {
    fun reversed(route: GpxTrack): GpxTrack = route.copy(
        segments = route.segments.asReversed().map { segment -> segment.asReversed() },
        routeAttributes = route.routeAttributes.asReversed(),
        navigationManeuvers = emptyList(),
    )

    fun isClosed(route: GpxTrack, maximumEndpointDistanceMeters: Double = 50.0): Boolean {
        val points = route.points
        return points.size >= 3 &&
            GeoMath.distanceMeters(points.first(), points.last()) <= maximumEndpointDistanceMeters
    }

    fun asOutAndBack(route: GpxTrack): GpxTrack {
        val outbound = route.points
        return route.copy(
            segments = listOf(outbound + outbound.dropLast(1).asReversed()),
            routeAttributes = route.routeAttributes + route.routeAttributes.asReversed(),
            navigationManeuvers = emptyList(),
        )
    }

    fun combineAsLoop(outbound: GpxTrack, inbound: GpxTrack): GpxTrack {
        val outboundPoints = outbound.points
        val inboundPoints = inbound.points
        require(outboundPoints.size >= 2 && inboundPoints.size >= 2) {
            localizedSystemText(
                "A loop requires an outbound and a return route.",
                "Für einen Rundweg werden ein Hin- und ein Rückweg benötigt.",
            )
        }
        return outbound.copy(
            name = localizedSystemText("Loop", "Rundweg"),
            segments = listOf(outboundPoints + inboundPoints.drop(1)),
            routeAttributes = outbound.routeAttributes + inbound.routeAttributes,
            navigationManeuvers = emptyList(),
        )
    }

    fun signature(route: GpxTrack): String = route.points.joinToString("|") { point ->
        "${String.format(Locale.US, "%.4f", point.latitude)},${String.format(Locale.US, "%.4f", point.longitude)}"
    }

    fun isGenuineLoop(
        route: GpxTrack,
        destination: TrackPoint,
        separationMeters: Double = DEFAULT_LOOP_SEPARATION_METERS,
        minimumDistinctFraction: Double = DEFAULT_MIN_DISTINCT_FRACTION,
    ): Boolean {
        val points = route.points
        if (points.size < 6) return false
        val destinationIndex = points.indices.minByOrNull { GeoMath.distanceMeters(points[it], destination) }
            ?: return false
        if (destinationIndex < 2 || destinationIndex > points.lastIndex - 2) return false
        val outbound = samplePoints(points.subList(0, destinationIndex + 1))
        val inbound = samplePoints(points.subList(destinationIndex, points.size))
        val distinctInbound = inbound.count { inboundPoint ->
            outbound.minOf { outboundPoint -> GeoMath.distanceMeters(inboundPoint, outboundPoint) } > separationMeters
        }
        return distinctInbound.toDouble() / inbound.size >= minimumDistinctFraction
    }

    private fun samplePoints(points: List<TrackPoint>): List<TrackPoint> {
        val step = (points.size / MAX_COMPARISON_POINTS).coerceAtLeast(1)
        return points.filterIndexed { index, _ -> index % step == 0 || index == points.lastIndex }
    }

    private const val DEFAULT_LOOP_SEPARATION_METERS = 30.0
    private const val DEFAULT_MIN_DISTINCT_FRACTION = 0.15
    private const val MAX_COMPARISON_POINTS = 180
}
