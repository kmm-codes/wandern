package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.service.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TourProgressFlowTest {
    @Test
    fun idleMapShowsOnlyBottomActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, TrackingService::class.java))
        val store = TrackStore(context)
        while (true) {
            val active = store.activeSession() ?: break
            store.discardSession(active.id)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var idleUiReady = false
            for (attempt in 0 until 50) {
                scenario.onActivity { activity ->
                    idleUiReady = activity.findViewById<View>(R.id.actionsCard).visibility == View.VISIBLE &&
                        activity.findViewById<View>(R.id.recordingCard).visibility == View.GONE
                }
                if (idleUiReady) break
                SystemClock.sleep(100)
            }
            scenario.onActivity { activity ->
                org.junit.Assert.assertTrue("Idle map did not settle within 5 seconds", idleUiReady)
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
