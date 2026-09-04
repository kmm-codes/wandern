package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
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
                assertEquals(
                    activity.getString(R.string.record),
                    activity.findViewById<MaterialButton>(R.id.recordButton).text.toString(),
                )
            }
        }
    }

    @Test
    fun loadedTourTurnsRecordButtonIntoStartTour() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, TrackingService::class.java))
        val store = TrackStore(context)
        while (true) {
            val active = store.activeSession() ?: break
            store.discardSession(active.id)
        }
        val stored = store.saveImportedTrack(
            GpxTrack(
                name = "Record-Button-${System.nanoTime()}",
                segments = listOf(
                    listOf(
                        TrackPoint(48.99, 8.47, elevationMeters = 120.0),
                        TrackPoint(49.00, 8.49, elevationMeters = 150.0),
                    ),
                ),
            ),
        )
        try {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TOUR_REFERENCE, stored.reference)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                var label = ""
                for (attempt in 0 until 50) {
                    scenario.onActivity { activity ->
                        label = activity.findViewById<MaterialButton>(R.id.recordButton).text.toString()
                    }
                    if (label == context.getString(R.string.start_tour)) break
                    SystemClock.sleep(100)
                }
                assertEquals(context.getString(R.string.start_tour), label)
                scenario.onActivity { activity ->
                    assertEquals(
                        View.VISIBLE,
                        activity.findViewById<View>(R.id.moreButton).visibility,
                    )
                }
            }
        } finally {
            store.deleteStoredTour(stored.reference)
        }
    }
}
