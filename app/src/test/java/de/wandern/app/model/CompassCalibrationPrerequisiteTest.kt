package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassCalibrationPrerequisiteTest {
    @Test
    fun doesNotUnlockWalkBeforeFigureEightConfirmation() {
        val prerequisite = CompassCalibrationPrerequisite(minimumSamples = 1, minimumStabilizationMillis = 0)

        val progress = prerequisite.onSensorSample(nowMillis = 10, qualityConfirmed = true)

        assertEquals(CompassCalibrationPrerequisite.State.FIGURE_EIGHT_REQUIRED, progress.state)
    }

    @Test
    fun requiresFreshStableAndReliableSamplesAfterConfirmation() {
        val prerequisite = CompassCalibrationPrerequisite(minimumSamples = 2, minimumStabilizationMillis = 1_000)
        prerequisite.confirmFigureEight(nowMillis = 1_000)

        assertEquals(
            CompassCalibrationPrerequisite.State.STABILIZING,
            prerequisite.onSensorSample(nowMillis = 1_500, qualityConfirmed = true).state,
        )
        assertEquals(
            CompassCalibrationPrerequisite.State.READY_TO_WALK,
            prerequisite.onSensorSample(nowMillis = 2_000, qualityConfirmed = true).state,
        )
    }

    @Test
    fun remainsBlockedWhenSensorStillReportsPoorQuality() {
        val prerequisite = CompassCalibrationPrerequisite(minimumSamples = 2, minimumStabilizationMillis = 1_000)
        prerequisite.confirmFigureEight(nowMillis = 1_000)

        prerequisite.onSensorSample(nowMillis = 2_000, qualityConfirmed = false)
        val progress = prerequisite.onSensorSample(nowMillis = 2_100, qualityConfirmed = false)

        assertEquals(CompassCalibrationPrerequisite.State.STABILIZING, progress.state)
    }

    @Test
    fun repeatingFigureEightClearsPreviousReadiness() {
        val prerequisite = CompassCalibrationPrerequisite(minimumSamples = 1, minimumStabilizationMillis = 0)
        prerequisite.confirmFigureEight(nowMillis = 1_000)
        prerequisite.onSensorSample(nowMillis = 1_000, qualityConfirmed = true)

        val progress = prerequisite.restart()

        assertEquals(CompassCalibrationPrerequisite.State.FIGURE_EIGHT_REQUIRED, progress.state)
        assertEquals(0, progress.sampleCount)
    }
}
