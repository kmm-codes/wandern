package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.ActivityType
import de.wandern.app.model.RecordingState
import de.wandern.app.service.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRecoveryFlowTest {
    @Test
    fun openingAppSurfacesPausedRecordingWithoutStartingAnotherOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        discardActiveSessions(store)
        val sessionId = store.createSession(activityType = ActivityType.E_BIKE)
        store.updateState(sessionId, RecordingState.PAUSED)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil(scenario) { activity ->
                    activity.findViewById<View>(R.id.recordingCard).visibility == View.VISIBLE
                }
                scenario.onActivity { activity ->
                    assertEquals(
                        "Aufzeichnung pausiert · E-Bike",
                        activity.findViewById<TextView>(R.id.recordingStatusText).text.toString(),
                    )
                }
            }
        } finally {
            context.startService(
                Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_DISCARD),
            )
            SystemClock.sleep(250)
            store.discardSession(sessionId)
            discardActiveSessions(store)
        }
    }

    private fun waitUntil(
        scenario: ActivityScenario<MainActivity>,
        condition: (MainActivity) -> Boolean,
    ) {
        repeat(50) {
            var satisfied = false
            scenario.onActivity { satisfied = condition(it) }
            if (satisfied) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Pausierte Aufzeichnung wurde nicht innerhalb von 5 Sekunden angezeigt.")
    }

    private fun discardActiveSessions(store: TrackStore) {
        while (true) {
            val active = store.activeSession() ?: return
            store.discardSession(active.id)
        }
    }
}
