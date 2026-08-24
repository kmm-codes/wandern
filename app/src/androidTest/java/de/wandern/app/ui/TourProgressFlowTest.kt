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
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TourProgressFlowTest {
    @Test
    fun openingStoredTourShowsInitialRouteProgress() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trackStore = TrackStore(context)
        val stored = trackStore.saveImportedTrack(testTrack())
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TOUR_REFERENCE, stored.reference)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                waitUntil(scenario) { activity ->
                    activity.findViewById<View>(R.id.routeProgressGroup).visibility == View.VISIBLE
                }
                scenario.onActivity { activity ->
                    assertEquals(
                        "CI-Panoramatour",
                        activity.findViewById<TextView>(R.id.titleText).text.toString(),
                    )
                    assertEquals(
                        "0 %",
                        activity.findViewById<TextView>(R.id.routeProgressText).text.toString(),
                    )
                    assertTrue(
                        activity.findViewById<TextView>(R.id.remainingDistanceText)
                            .text.toString().endsWith("km"),
                    )
                    assertTrue(
                        activity.findViewById<TextView>(R.id.etaText).text.toString() != "—",
                    )
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.moreButton).visibility)
                }
            }
        } finally {
            trackStore.deleteStoredTour(stored.reference)
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
        throw AssertionError("Tourfortschritt wurde nicht innerhalb von 5 Sekunden angezeigt.")
    }

    private fun testTrack() = GpxTrack(
        name = "CI-Panoramatour",
        segments = listOf(
            (0..15).map { index ->
                TrackPoint(
                    latitude = 48.75 + index * 0.001,
                    longitude = 8.23 + index * 0.001,
                    elevationMeters = 200.0 + index * 12.0,
                )
            },
        ),
    )
}
