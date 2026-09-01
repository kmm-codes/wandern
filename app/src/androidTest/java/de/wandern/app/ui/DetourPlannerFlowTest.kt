package de.wandern.app.ui

import android.content.Intent
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetourPlannerFlowTest {
    @Test
    fun opensWithVisibleAdjustableCorridorBeforeRouting() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = TrackStore(context)
        val route = GpxTrack(
            name = "Umleitung UI-Test",
            segments = listOf((0..30).map { TrackPoint(48.0, 8.0 + it * 0.001) }),
            activityType = ActivityType.HIKING,
        )
        val stored = store.saveImportedTrack(route)
        val sessionId = store.createSession(route.name, stored.reference, ActivityType.HIKING)
        val intent = Intent(context, DetourPlannerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DetourPlannerActivity.EXTRA_SESSION_ID, sessionId)
            .putExtra(DetourPlannerActivity.EXTRA_LATITUDE, 48.0)
            .putExtra(DetourPlannerActivity.EXTRA_LONGITUDE, 8.001)
            .putExtra(DetourPlannerActivity.EXTRA_PROGRESS_METERS, 80.0)

        try {
            ActivityScenario.launch<DetourPlannerActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(
                        activity.getString(R.string.detour_corridor_length, 200),
                        activity.findViewById<TextView>(R.id.corridorLengthText).text.toString(),
                    )
                    assertFalse(activity.findViewById<MaterialButton>(R.id.useDetourButton).isEnabled)
                    val slider = activity.findViewById<SeekBar>(R.id.corridorLengthSlider)
                    slider.progress = 320
                    assertEquals(
                        activity.getString(R.string.detour_corridor_length, 400),
                        activity.findViewById<TextView>(R.id.corridorLengthText).text.toString(),
                    )
                }
            }
        } finally {
            store.discardSession(sessionId)
            store.deleteStoredTour(stored.reference)
        }
    }
}
