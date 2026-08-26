package de.wandern.app.model

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val timeMillis: Long? = null,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val isInterpolated: Boolean = false,
    val bearingDegrees: Float? = null,
)

object GpsQuality {
    const val RELIABLE_ACCURACY_METERS = 35f
}

data class GpxTrack(
    val name: String,
    val segments: List<List<TrackPoint>>,
    val elevationSource: ElevationSource? = null,
    val activityType: ActivityType? = null,
) {
    val points: List<TrackPoint> get() = segments.flatten()

    fun reversed(): GpxTrack = copy(
        segments = segments.asReversed().map { it.asReversed() },
    )

    companion object {
        fun empty(name: String = "Unbenannte Tour") = GpxTrack(name, emptyList())
    }
}

fun GpxTrack.asRouteDefinition(): GpxTrack = copy(
    segments = segments.map { segment ->
        segment.map { point ->
            point.copy(
                timeMillis = null,
                accuracyMeters = null,
                speedMetersPerSecond = null,
                isInterpolated = false,
                bearingDegrees = null,
            )
        }
    },
)

enum class ElevationSource {
    OPEN_METEO_COPERNICUS_GLO_90,
}

data class TrackStats(
    val distanceMeters: Double = 0.0,
    val durationMillis: Long = 0L,
    val movingDurationMillis: Long = 0L,
    val pauseDurationMillis: Long = 0L,
    val pauseCount: Int = 0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val paceSecondsPerKilometer: Double? = null,
    val currentSlopePercent: Double? = null,
    val pointCount: Int = 0,
)

enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED,
    FINISHED,
}

data class TrackingSnapshot(
    val state: RecordingState = RecordingState.IDLE,
    val track: GpxTrack = GpxTrack.empty(),
    val stats: TrackStats = TrackStats(),
    val latestPoint: TrackPoint? = null,
    val latestObservedPoint: TrackPoint? = null,
    val savedTrackPath: String? = null,
    val errorMessage: String? = null,
    val gpsGapActive: Boolean = false,
    val autoPaused: Boolean = false,
    val routeDeviationMeters: Double? = null,
    val confirmedOffRoute: Boolean = false,
    val activityType: ActivityType? = null,
)
