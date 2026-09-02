package de.wandern.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.wandern.app.R
import de.wandern.app.data.RecordingRouteStore
import de.wandern.app.data.TrackStore
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RecordingState
import de.wandern.app.model.TrackPoint
import de.wandern.app.service.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRecoveryFlowTest {
    @Test
    fun recreatingAppRestoresRouteForActiveRecording() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        val store = TrackStore(context)
        discardActiveSessions(store)
        val storedRoute = store.saveImportedTrack(testRoute())
        val sessionId = store.createSession(
            routeName = storedRoute.name,
            routeReference = storedRoute.reference,
            activityType = ActivityType.HIKING,
        )
        store.updateState(sessionId, RecordingState.PAUSED)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil(scenario) { activity ->
                    activity.findViewById<View>(R.id.recordingRouteProgressGroup).visibility == View.VISIBLE
                }

                scenario.recreate()

                waitUntil(scenario) { activity ->
                    activity.findViewById<View>(R.id.recordingRouteProgressGroup).visibility == View.VISIBLE
                }
                scenario.onActivity { activity ->
                    assertEquals(
                        "0 %",
                        activity.findViewById<TextView>(R.id.recordingRouteProgressText).text.toString(),
                    )
                    assertEquals(
                        View.VISIBLE,
                        activity.findViewById<View>(R.id.recordingElevationChart).visibility,
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
            store.deleteStoredTour(storedRoute.reference)
            discardActiveSessions(store)
        }
    }

    @Test
    fun openingAppSurfacesPausedRecordingWithoutStartingAnotherOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        context.stopService(Intent(context, TrackingService::class.java))
        discardActiveSessions(store)
        val sessionId = store.createSession(activityType = ActivityType.E_BIKE)
        store.updateState(sessionId, RecordingState.PAUSED)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil(scenario) { activity ->
                    activity.findViewById<TextView>(R.id.recordingStatusText).text.toString() ==
                        "${activity.getString(R.string.recording_paused)} · " +
                        activity.getString(ActivityType.E_BIKE.labelRes())
                }
                scenario.onActivity { activity ->
                    assertEquals(
                        "${activity.getString(R.string.recording_paused)} · " +
                            activity.getString(ActivityType.E_BIKE.labelRes()),
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

    @Test
    fun recordingOnlyRouteOffersDetourAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val recordingRoutes = RecordingRouteStore(context)
        discardActiveSessions(store)
        val sessionId = store.createSession(activityType = ActivityType.HIKING)
        recordingRoutes.save(sessionId, testRoute(), emptyList())
        store.updateState(sessionId, RecordingState.PAUSED)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil(scenario) { activity ->
                    activity.findViewById<View>(R.id.recordingDetourActions).visibility == View.VISIBLE
                }
            }
        } finally {
            context.startService(
                Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_DISCARD),
            )
            SystemClock.sleep(250)
            recordingRoutes.clear(sessionId)
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

    private fun grantNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private fun testRoute() = GpxTrack(
        name = "CI-Wiederherstellungsroute",
        segments = listOf(
            (0..12).map { index ->
                TrackPoint(
                    latitude = 48.75 + index * 0.001,
                    longitude = 8.23 + index * 0.001,
                    elevationMeters = 200.0 + index * 8.0,
                )
            },
        ),
    )
}
