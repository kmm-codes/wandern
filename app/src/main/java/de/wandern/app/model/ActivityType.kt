package de.wandern.app.model

enum class ActivityType(
    val gpxValue: String,
) {
    HIKING("hiking"),
    CYCLING("cycling"),
    E_BIKE("e-biking"),
    RUNNING("running"),
    ;

    companion object {
        fun fromStoredValue(value: String?): ActivityType =
            entries.firstOrNull { it.name == value } ?: HIKING

        fun fromStoredValueOrNull(value: String?): ActivityType? =
            entries.firstOrNull { it.name == value }

        fun fromGpxValue(value: String?): ActivityType? = when (
            value?.trim()?.lowercase()?.replace('_', '-')
        ) {
            "hiking", "walking", "foot" -> HIKING
            "cycling", "biking", "bike", "bicycle" -> CYCLING
            "e-biking", "ebiking", "e-bike", "ebike" -> E_BIKE
            "running", "run", "jogging" -> RUNNING
            else -> null
        }
    }
}
