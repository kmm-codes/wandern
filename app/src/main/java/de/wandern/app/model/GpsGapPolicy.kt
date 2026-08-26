package de.wandern.app.model

enum class GpsGapAction {
    NONE,
    INTERPOLATE,
    START_NEW_SEGMENT,
}

object GpsGapPolicy {
    fun decide(
        previous: TrackPoint,
        current: TrackPoint,
        elapsedMillis: Long,
        activityType: ActivityType,
        interpolationThresholdMillis: Long = 15_000L,
        maximumInterpolationMillis: Long = 90_000L,
    ): GpsGapAction {
        if (elapsedMillis < interpolationThresholdMillis) return GpsGapAction.NONE
        if (elapsedMillis > maximumInterpolationMillis) return GpsGapAction.START_NEW_SEGMENT
        if (elapsedMillis <= 0L) return GpsGapAction.START_NEW_SEGMENT

        val uncertainty = (previous.accuracyMeters ?: 0f) + (current.accuracyMeters ?: 0f)
        val effectiveDistance = (GeoMath.distanceMeters(previous, current) - uncertainty)
            .coerceAtLeast(0.0)
        val effectiveSpeed = effectiveDistance / (elapsedMillis / 1_000.0)
        return if (
            effectiveSpeed <= LocationSamplePipeline.maximumSpeedMetersPerSecond(activityType)
        ) {
            GpsGapAction.INTERPOLATE
        } else {
            GpsGapAction.START_NEW_SEGMENT
        }
    }
}
