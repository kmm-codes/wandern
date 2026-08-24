package de.wandern.app.model

import kotlin.math.max

enum class AutoPauseTransition {
    NONE,
    PAUSED,
    RESUMED,
}

data class AutoPauseUpdate(
    val autoPaused: Boolean,
    val stationaryEvidence: Boolean,
    val transition: AutoPauseTransition = AutoPauseTransition.NONE,
)

/**
 * Detects a deliberate stop from several reliable fixes. It does not represent a manual pause;
 * callers keep listening for locations so movement can resume automatically.
 */
class AutoPauseDetector(
    private val pauseAfterMillis: Long = 20_000L,
    private val resumeAfterMillis: Long = 6_000L,
    private val stationarySpeedMetersPerSecond: Float = 0.25f,
    private val movingSpeedMetersPerSecond: Float = 0.55f,
    private val minimumMovementRadiusMeters: Double = 8.0,
    private val reliableAccuracyMeters: Float = GpsQuality.RELIABLE_ACCURACY_METERS,
) {
    private enum class MotionEvidence { STATIONARY, MOVING, AMBIGUOUS }

    private var anchor: TrackPoint? = null
    private var previous: TrackPoint? = null
    private var stationarySinceMillis: Long? = null
    private var movingSinceMillis: Long? = null
    private var stationarySampleCount = 0
    private var movingSampleCount = 0
    private var paused = false
    private var movementObserved = false
    private var previousObservationTimeMillis: Long? = null

    fun update(
        point: TrackPoint,
        observationTimeMillis: Long? = point.timeMillis,
    ): AutoPauseUpdate {
        val timeMillis = observationTimeMillis ?: return currentUpdate(stationaryEvidence = false)
        if ((point.accuracyMeters ?: 0f) > reliableAccuracyMeters) {
            return currentUpdate(stationaryEvidence = false)
        }
        val previousTime = previousObservationTimeMillis
        if (previousTime != null && timeMillis < previousTime) {
            return currentUpdate(stationaryEvidence = false)
        }
        if (anchor == null) anchor = point

        val evidence = motionEvidence(point)
        previous = point
        previousObservationTimeMillis = timeMillis
        return if (paused) updateWhilePaused(point, timeMillis, evidence) else {
            updateWhileMoving(point, timeMillis, evidence)
        }
    }

    fun reset() {
        anchor = null
        previous = null
        stationarySinceMillis = null
        movingSinceMillis = null
        stationarySampleCount = 0
        movingSampleCount = 0
        paused = false
        movementObserved = false
        previousObservationTimeMillis = null
    }

    private fun updateWhileMoving(
        point: TrackPoint,
        timeMillis: Long,
        evidence: MotionEvidence,
    ): AutoPauseUpdate = when (evidence) {
        MotionEvidence.STATIONARY -> {
            if (!movementObserved) {
                stationarySinceMillis = null
                stationarySampleCount = 0
                AutoPauseUpdate(false, stationaryEvidence = true)
            } else {
                stationarySampleCount += 1
                val stationarySince = stationarySinceMillis ?: timeMillis.also {
                    stationarySinceMillis = it
                }
                if (
                    stationarySampleCount >= MIN_STATIONARY_SAMPLES &&
                    timeMillis - stationarySince >= pauseAfterMillis
                ) {
                    paused = true
                    movingSinceMillis = null
                    movingSampleCount = 0
                    AutoPauseUpdate(true, stationaryEvidence = true, AutoPauseTransition.PAUSED)
                } else {
                    AutoPauseUpdate(false, stationaryEvidence = true)
                }
            }
        }
        MotionEvidence.MOVING -> {
            movementObserved = true
            anchor = point
            stationarySinceMillis = null
            stationarySampleCount = 0
            AutoPauseUpdate(false, stationaryEvidence = false)
        }
        MotionEvidence.AMBIGUOUS -> {
            stationarySinceMillis = null
            stationarySampleCount = 0
            AutoPauseUpdate(false, stationaryEvidence = false)
        }
    }

    private fun updateWhilePaused(
        point: TrackPoint,
        timeMillis: Long,
        evidence: MotionEvidence,
    ): AutoPauseUpdate = when (evidence) {
        MotionEvidence.MOVING -> {
            movingSampleCount += 1
            val movingSince = movingSinceMillis ?: timeMillis.also { movingSinceMillis = it }
            if (movingSampleCount >= MIN_MOVING_SAMPLES && timeMillis - movingSince >= resumeAfterMillis) {
                paused = false
                anchor = point
                stationarySinceMillis = null
                movingSinceMillis = null
                stationarySampleCount = 0
                movingSampleCount = 0
                AutoPauseUpdate(false, stationaryEvidence = false, AutoPauseTransition.RESUMED)
            } else {
                AutoPauseUpdate(true, stationaryEvidence = false)
            }
        }
        MotionEvidence.STATIONARY, MotionEvidence.AMBIGUOUS -> {
            movingSinceMillis = null
            movingSampleCount = 0
            AutoPauseUpdate(true, stationaryEvidence = evidence == MotionEvidence.STATIONARY)
        }
    }

    private fun motionEvidence(point: TrackPoint): MotionEvidence {
        val speed = point.speedMetersPerSecond
        if (speed != null && speed >= movingSpeedMetersPerSecond) return MotionEvidence.MOVING

        val reference = anchor ?: point
        val displacement = GeoMath.distanceMeters(reference, point)
        val accuracyRadius = max(
            reference.accuracyMeters?.toDouble() ?: 0.0,
            point.accuracyMeters?.toDouble() ?: 0.0,
        ) * ACCURACY_RADIUS_FACTOR
        val movementRadius = max(minimumMovementRadiusMeters, accuracyRadius)
        if (displacement > movementRadius) return MotionEvidence.MOVING
        if (speed != null && speed <= stationarySpeedMetersPerSecond) return MotionEvidence.STATIONARY
        return if (previous != null) MotionEvidence.STATIONARY else MotionEvidence.AMBIGUOUS
    }

    private fun currentUpdate(stationaryEvidence: Boolean) =
        AutoPauseUpdate(autoPaused = paused, stationaryEvidence = stationaryEvidence)

    companion object {
        private const val ACCURACY_RADIUS_FACTOR = 1.5
        private const val MIN_STATIONARY_SAMPLES = 3
        private const val MIN_MOVING_SAMPLES = 2
    }
}
