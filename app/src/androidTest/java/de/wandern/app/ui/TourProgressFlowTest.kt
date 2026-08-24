package de.wandern.app.ui

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TourProgressFlowTest {
    @Test
    fun idleMapShowsOnlyBottomActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.actionsCard).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.recordingCard).visibility)
                assertEquals(
                    0,
                    activity.resources.getIdentifier("headerCard", "id", activity.packageName),
                )
            }
        }
    }
}
