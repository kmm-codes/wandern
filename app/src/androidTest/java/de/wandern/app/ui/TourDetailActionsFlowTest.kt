package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TourDetailActionsFlowTest {
    @Test
    fun plannedTourDetailsOfferTheSameCoreActionsAsTheLibrary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val stored = store.saveImportedTrack(
            GpxTrack(
                name = "Detailaktionen-${System.nanoTime()}",
                segments = listOf(
                    listOf(
                        TrackPoint(48.99, 8.47, elevationMeters = 120.0),
                        TrackPoint(49.00, 8.49, elevationMeters = 150.0),
                    ),
                ),
            ),
        )

        try {
            val intent = Intent(context, TourDetailActivity::class.java)
                .putExtra(TourDetailActivity.EXTRA_TOUR_REFERENCE, stored.reference)
            ActivityScenario.launch<TourDetailActivity>(intent).use { scenario ->
                var titles = emptyList<String>()
                for (attempt in 0 until 60) {
                    scenario.onActivity { activity ->
                        val menu = activity.findViewById<MaterialToolbar>(R.id.toolbar).menu
                        titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }
                    }
                    if (activityCoreTitles(context).all(titles::contains)) break
                    SystemClock.sleep(50)
                }

                assertTrue(activityCoreTitles(context).all(titles::contains))
                assertEquals(context.getString(R.string.delete), titles.last())
            }
        } finally {
            store.deleteStoredTour(stored.reference)
        }
    }

    private fun activityCoreTitles(context: Context): List<String> = listOf(
        context.getString(R.string.edit_tour),
        context.getString(R.string.duplicate_tour),
        context.getString(R.string.rename_tour),
        context.getString(R.string.export_gpx),
        context.getString(R.string.delete),
    )
}
