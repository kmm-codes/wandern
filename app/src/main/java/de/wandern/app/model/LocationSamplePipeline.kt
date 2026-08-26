package de.wandern.app.model

import kotlin.math.max

enum class LocationSampleSource {
    GPS,
    NETWORK,
    OTHER,
}

data class LocationSample(
    val point: TrackPoint,
    val elapsedRealtimeMillis: Long,
    val source: LocationSampleSource,
)

enum class LocationSampleDecisionReason {
    ACCEPTED,
    POOR_ACCURACY,
    OUT_OF_ORDER,
    NETWORK_SUPPRESSED,
    AWAITING_CONFIRMATION,
    OUTLIER_REPLACED,
    DISCONTINUITY_CONFIRMED,
}

data class LocationSampleDecision(
    val trustedSamples: List<LocationSample> = emptyList(),
    val startNewSegment: Boolean = false,
    val reason: LocationSampleDecisionReason,
    val marksGpsGap: Boolean = false,
)

/**
 * Keeps isolated location jumps out of a recording without delaying normal fixes.
 *
 * A physically suspicious fix is held until the next reliable measurement either confirms the
 * displaced position or returns to the previous trajectory. Provider and monotonic timing metadata
 * intentionally stay outside [TrackPoint], so stored GPX data remains portable.
 */
class LocationSamplePipeline(
    private var activityType: ActivityType,
    private val reliableAccuracyMeters: Float = GpsQuality.RELIABLE_ACCURACY_METERS,
    private val gpsPriorityMillis: Long = 12_000L,
) {
    private data class TrustedLocation(
        val point: TrackPoint,
        val elapsedRealtimeMillis: Long?,
    )

    private var lastTrusted: TrustedLocation? = null
    private var pending: LocationSample? = null
    private var lastObservedElapsedRealtimeMillis: Long? = null
    private var lastTrustedGpsElapsedRealtimeMillis: Long? = null

    fun setActivityType(activityType: ActivityType) {
        this.activityType = activityType
    }

    fun reset(seed: TrackPoint? = null) {
        lastTrusted = seed?.let { TrustedLocation(it, null) }
        pending = null
        lastObservedElapsedRealtimeMillis = null
        lastTrustedGpsElapsedRealtimeMillis = null
    }

    fun process(sample: LocationSample): LocationSampleDecision {
        val previousObservation = lastObservedElapsedRealtimeMillis
        if (previousObservation != null && sample.elapsedRealtimeMillis < previousObservation) {
            return LocationSampleDecision(reason = LocationSampleDecisionReason.OUT_OF_ORDER)
        }
        lastObservedElapsedRealtimeMillis = sample.elapsedRealtimeMillis

        val accuracy = sample.point.accuracyMeters
        if (accuracy == null || accuracy > reliableAccuracyMeters) {
            return LocationSampleDecision(
                reason = LocationSampleDecisionReason.POOR_ACCURACY,
                marksGpsGap = lastTrusted != null,
            )
        }

        val recentGps = lastTrustedGpsElapsedRealtimeMillis
        if (
            sample.source == LocationSampleSource.NETWORK &&
            recentGps != null &&
            sample.elapsedRealtimeMillis - recentGps in 0..gpsPriorityMillis
        ) {
            return LocationSampleDecision(reason = LocationSampleDecisionReason.NETWORK_SUPPRESSED)
        }

        val trusted = lastTrusted
        if (trusted == null) return accept(listOf(sample), LocationSampleDecisionReason.ACCEPTED)

        val waiting = pending
        if (waiting == null) {
            return if (isPlausible(trusted, sample)) {
                accept(listOf(sample), LocationSampleDecisionReason.ACCEPTED)
            } else {
                pending = sample
                LocationSampleDecision(
                    reason = LocationSampleDecisionReason.AWAITING_CONFIRMATION,
                    marksGpsGap = true,
                )
            }
        }

        val currentContinuesFromTrusted = isPlausible(trusted, sample)
        val currentConfirmsPending = isPlausible(waiting.asTrusted(), sample) ||
            isSameLocationCluster(waiting, sample)
        return when {
            currentContinuesFromTrusted && currentConfirmsPending -> {
                pending = null
                accept(listOf(waiting, sample), LocationSampleDecisionReason.ACCEPTED)
            }
            currentContinuesFromTrusted -> {
                pending = null
                accept(listOf(sample), LocationSampleDecisionReason.OUTLIER_REPLACED)
            }
            currentConfirmsPending -> {
                pending = null
                accept(
                    samples = listOf(waiting, sample),
                    reason = LocationSampleDecisionReason.DISCONTINUITY_CONFIRMED,
                    startNewSegment = true,
                )
            }
            else -> {
                pending = sample
                LocationSampleDecision(
                    reason = LocationSampleDecisionReason.AWAITING_CONFIRMATION,
                    marksGpsGap = true,
                )
            }
        }
    }

    private fun accept(
        samples: List<LocationSample>,
        reason: LocationSampleDecisionReason,
        startNewSegment: Boolean = false,
    ): LocationSampleDecision {
        samples.forEach { sample ->
            lastTrusted = sample.asTrusted()
            if (sample.source == LocationSampleSource.GPS) {
                lastTrustedGpsElapsedRealtimeMillis = sample.elapsedRealtimeMillis
            }
        }
        return LocationSampleDecision(
            trustedSamples = samples,
            startNewSegment = startNewSegment,
            reason = reason,
        )
    }

    private fun isPlausible(previous: TrustedLocation, current: LocationSample): Boolean {
        val maximumSpeed = maximumSpeedMetersPerSecond(activityType)
        val reportedSpeed = current.point.speedMetersPerSecond
        if (reportedSpeed != null && reportedSpeed > maximumSpeed * REPORTED_SPEED_TOLERANCE) {
            return false
        }

        val distance = GeoMath.distanceMeters(previous.point, current.point)
        val uncertainty = (previous.point.accuracyMeters ?: reliableAccuracyMeters) +
            (current.point.accuracyMeters ?: reliableAccuracyMeters)
        val previousElapsed = previous.elapsedRealtimeMillis
        if (previousElapsed == null) {
            return distance <= max(RECOVERY_PROXIMITY_METERS, uncertainty * 2.0)
        }
        val elapsedMillis = current.elapsedRealtimeMillis - previousElapsed
        if (elapsedMillis <= 0L) return distance <= uncertainty
        val rawSpeed = distance / (elapsedMillis / 1_000.0)
        if (rawSpeed > maximumSpeed * RAW_SPEED_TOLERANCE) return false
        val effectiveDistance = (distance - uncertainty).coerceAtLeast(0.0)
        return effectiveDistance / (elapsedMillis / 1_000.0) <= maximumSpeed
    }

    private fun isSameLocationCluster(first: LocationSample, second: LocationSample): Boolean {
        val uncertainty = (first.point.accuracyMeters ?: reliableAccuracyMeters) +
            (second.point.accuracyMeters ?: reliableAccuracyMeters)
        val clusterRadius = max(MINIMUM_CONFIRMATION_RADIUS_METERS, uncertainty * 1.5)
        return GeoMath.distanceMeters(first.point, second.point) <= clusterRadius
    }

    private fun LocationSample.asTrusted() = TrustedLocation(
        point = point,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    companion object {
        private const val REPORTED_SPEED_TOLERANCE = 1.25
        private const val RAW_SPEED_TOLERANCE = 1.5
        private const val RECOVERY_PROXIMITY_METERS = 100.0
        private const val MINIMUM_CONFIRMATION_RADIUS_METERS = 25.0

        fun maximumSpeedMetersPerSecond(activityType: ActivityType): Double = when (activityType) {
            ActivityType.HIKING -> 4.5
            ActivityType.RUNNING -> 8.5
            ActivityType.CYCLING,
            ActivityType.E_BIKE,
            -> 22.0
        }
    }
}
