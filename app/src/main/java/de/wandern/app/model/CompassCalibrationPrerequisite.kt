package de.wandern.app.model

class CompassCalibrationPrerequisite(
    private val minimumSamples: Int = 8,
    private val minimumStabilizationMillis: Long = 1_500L,
) {
    enum class State { FIGURE_EIGHT_REQUIRED, STABILIZING, READY }

    data class Progress(
        val state: State,
        val sampleCount: Int = 0,
        val sensorQualityConfirmed: Boolean = false,
    )

    private var state = State.FIGURE_EIGHT_REQUIRED
    private var stabilizationStartedAtMillis = 0L
    private var sampleCount = 0
    private var sensorQualityConfirmed = false

    fun confirmFigureEight(nowMillis: Long): Progress {
        state = State.STABILIZING
        stabilizationStartedAtMillis = nowMillis
        sampleCount = 0
        sensorQualityConfirmed = false
        return progress()
    }

    fun onSensorSample(nowMillis: Long, qualityConfirmed: Boolean): Progress {
        if (state != State.STABILIZING) return progress()
        sampleCount++
        sensorQualityConfirmed = sensorQualityConfirmed || qualityConfirmed
        if (
            sampleCount >= minimumSamples &&
            nowMillis - stabilizationStartedAtMillis >= minimumStabilizationMillis &&
            sensorQualityConfirmed
        ) {
            state = State.READY
        }
        return progress()
    }

    fun restart(): Progress {
        state = State.FIGURE_EIGHT_REQUIRED
        stabilizationStartedAtMillis = 0L
        sampleCount = 0
        sensorQualityConfirmed = false
        return progress()
    }

    fun progress(): Progress = Progress(state, sampleCount, sensorQualityConfirmed)
}
