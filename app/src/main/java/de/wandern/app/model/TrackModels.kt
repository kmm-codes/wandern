package de.wandern.app.model

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val timeMillis: Long? = null,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val isInterpolated: Boolean = false,
)

object GpsQuality {
    const val RELIABLE_ACCURACY_METERS = 35f
}

data class GpxTrack(
    val name: String,
    val segments: List<List<TrackPoint>>,
    val elevationSource: ElevationSource? = null,
) {
    val points: List<TrackPoint> get() = segments.flatten()

    companion object {
        fun empty(name: String = "Unbenannte Tour") = GpxTrack(name, emptyList())
    }
}

enum class ElevationSource {
    OPEN_METEO_COPERNICUS_GLO_90,
}

data class TrackStats(
    val distanceMeters: Double = 0.0,
    val durationMillis: Long = 0L,
    val movingDurationMillis: Long = 0L,
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
    val savedTrackPath: String? = null,
    val errorMessage: String? = null,
    val gpsGapActive: Boolean = false,
)
