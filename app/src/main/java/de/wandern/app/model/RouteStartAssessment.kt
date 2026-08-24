package de.wandern.app.model

enum class RouteStartSituation {
    AT_START,
    ON_ROUTE,
    NEAR_ROUTE,
    AWAY_FROM_ROUTE,
}

data class RouteStartAssessment(
    val situation: RouteStartSituation,
    val distanceToStartMeters: Double,
    val distanceToRouteMeters: Double,
    val distanceAlongRouteMeters: Double?,
    val recommendedEntryMode: RouteEntryMode,
)

object RouteStartAssessor {
    private const val START_PROXIMITY_METERS = 75.0
    private const val ON_ROUTE_METERS = 60.0
    private const val NEAR_ROUTE_METERS = 250.0

    fun assess(track: GpxTrack, position: TrackPoint): RouteStartAssessment? {
        val start = track.points.firstOrNull() ?: return null
        val distanceToStart = GeoMath.distanceMeters(position, start)
        val progress = RouteProgressCalculator(track).calculate(position)
        val distanceToRoute = progress?.distanceFromRouteMeters
            ?: GeoMath.distanceToTrackMeters(position, track)
            ?: return null
        val situation = when {
            distanceToStart <= START_PROXIMITY_METERS -> RouteStartSituation.AT_START
            distanceToRoute <= ON_ROUTE_METERS -> RouteStartSituation.ON_ROUTE
            distanceToRoute <= NEAR_ROUTE_METERS -> RouteStartSituation.NEAR_ROUTE
            else -> RouteStartSituation.AWAY_FROM_ROUTE
        }
        return RouteStartAssessment(
            situation = situation,
            distanceToStartMeters = distanceToStart,
            distanceToRouteMeters = distanceToRoute,
            distanceAlongRouteMeters = progress?.distanceAlongRouteMeters,
            recommendedEntryMode = when (situation) {
                RouteStartSituation.AT_START,
                RouteStartSituation.AWAY_FROM_ROUTE,
                -> RouteEntryMode.OFFICIAL_START
                RouteStartSituation.ON_ROUTE,
                RouteStartSituation.NEAR_ROUTE,
                -> RouteEntryMode.NEAREST_POINT
            },
        )
    }
}
