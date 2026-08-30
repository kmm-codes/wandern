package de.wandern.app.ui

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompassCalibrationFlowTest {
    @Test
    fun opensWithDedicatedFigureEightStepAndAnimation() {
        ActivityScenario.launch(CompassCalibrationActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.compass_calibration_figure_eight_title),
                    activity.findViewById<android.widget.TextView>(R.id.titleText).text.toString(),
                )
                val primaryButton = activity.findViewById<android.widget.Button>(R.id.primaryButton)
                assertEquals(
                    activity.getString(R.string.compass_calibration_figure_eight_confirm),
                    primaryButton.text.toString(),
                )
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.figureEightGuide).visibility)
                assertTrue(activity.findViewById<View>(R.id.mapPreview).visibility != View.VISIBLE)
                if (primaryButton.isEnabled) {
                    primaryButton.performClick()
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.figureEightGuide).visibility)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mapPreview).visibility)
                }
            }
        }
    }
}
