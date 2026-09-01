package de.wandern.app.model

import de.wandern.app.localization.localizedSystemText
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
    val routeAttributes: List<RouteAttributeSegment> = emptyList(),
) {
    val points: List<TrackPoint> get() = segments.flatten()

    fun reversed(): GpxTrack = copy(
        segments = segments.asReversed().map { it.asReversed() },
        routeAttributes = routeAttributes.asReversed(),
    )

    companion object {
        fun empty(name: String = localizedSystemText("Unnamed tour", "Unbenannte Tour")) =
            GpxTrack(name, emptyList())
    }
}

data class RouteAttributeSegment(
    val distanceMeters: Double,
    val wayType: RouteWayType,
    val surface: RouteSurface,
)

enum class RouteWayType {
    MOUNTAIN_TRAIL,
    HIKING_TRAIL,
    TRACK,
    FOOTWAY,
    MINOR_ROAD,
    ROAD,
    UNKNOWN,
}

enum class RouteSurface {
    ASPHALT,
    PAVED,
    COMPACTED,
    GRAVEL,
    NATURAL,
    UNKNOWN,
}

object RouteAttributeClassifier {
    fun classify(distanceMeters: Double, wayTags: Map<String, String>): RouteAttributeSegment? {
        if (!distanceMeters.isFinite() || distanceMeters <= 0.0) return null
        return RouteAttributeSegment(
            distanceMeters = distanceMeters,
            wayType = classifyWayType(wayTags),
            surface = classifySurface(wayTags),
        )
    }

    private fun classifyWayType(tags: Map<String, String>): RouteWayType {
        val highway = tags["highway"]?.lowercase().orEmpty()
        val sacScale = tags["sac_scale"]?.lowercase().orEmpty()
        return when {
            sacScale.isNotEmpty() && sacScale != "hiking" -> RouteWayType.MOUNTAIN_TRAIL
            highway == "path" && sacScale.isNotEmpty() -> RouteWayType.MOUNTAIN_TRAIL
            highway == "path" -> RouteWayType.HIKING_TRAIL
            highway == "track" -> RouteWayType.TRACK
            highway in FOOTWAY_VALUES -> RouteWayType.FOOTWAY
            highway in MINOR_ROAD_VALUES -> RouteWayType.MINOR_ROAD
            highway.isNotEmpty() -> RouteWayType.ROAD
            else -> RouteWayType.UNKNOWN
        }
    }

    private fun classifySurface(tags: Map<String, String>): RouteSurface {
        val surface = tags["surface"]?.lowercase().orEmpty()
        return when {
            surface == "asphalt" -> RouteSurface.ASPHALT
            surface in PAVED_SURFACES -> RouteSurface.PAVED
            surface in COMPACTED_SURFACES -> RouteSurface.COMPACTED
            surface in GRAVEL_SURFACES -> RouteSurface.GRAVEL
            surface in NATURAL_SURFACES -> RouteSurface.NATURAL
            surface.isNotEmpty() -> RouteSurface.UNKNOWN
            tags["tracktype"]?.lowercase() in setOf("grade1", "grade2") -> RouteSurface.COMPACTED
            tags["tracktype"]?.lowercase() in setOf("grade3", "grade4", "grade5") -> RouteSurface.NATURAL
            else -> RouteSurface.UNKNOWN
        }
    }

    fun mergeAdjacent(segments: List<RouteAttributeSegment>): List<RouteAttributeSegment> = buildList {
        segments.forEach { segment ->
            val previous = lastOrNull()
            if (previous != null && previous.wayType == segment.wayType && previous.surface == segment.surface) {
                this[lastIndex] = previous.copy(distanceMeters = previous.distanceMeters + segment.distanceMeters)
            } else {
                add(segment)
            }
        }
    }

    private val FOOTWAY_VALUES = setOf("footway", "pedestrian", "steps", "corridor")
    private val MINOR_ROAD_VALUES = setOf("living_street", "residential", "service", "unclassified", "road")
    private val PAVED_SURFACES = setOf(
        "paved", "concrete", "concrete:lanes", "concrete:plates", "paving_stones", "sett",
        "cobblestone", "unhewn_cobblestone", "metal", "wood",
    )
    private val COMPACTED_SURFACES = setOf("compacted", "fine_gravel")
    private val GRAVEL_SURFACES = setOf("gravel", "pebblestone", "gravel_turf")
    private val NATURAL_SURFACES = setOf(
        "unpaved", "dirt", "earth", "ground", "grass", "grass_paver", "mud", "sand", "rock",
        "bare_rock", "scree", "woodchips", "snow", "ice", "salt",
    )
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
    val capturedAtElapsedRealtimeMillis: Long = 0L,
    val movementTimeRunning: Boolean = false,
)
