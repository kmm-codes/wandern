package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TourMapDrawerFlowTest {
    @Test
    fun tourDataUsesDraggableExpandedAndCollapsedBottomSheetStates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val stored = store.saveImportedTrack(
            GpxTrack(
                name = "Tour-Drawer-${System.nanoTime()}",
                segments = listOf(
                    listOf(
                        TrackPoint(48.99, 8.47, elevationMeters = 120.0),
                        TrackPoint(49.00, 8.49, elevationMeters = 150.0),
                    ),
                ),
            ),
        )

        try {
            val intent = Intent(context, TourMapActivity::class.java)
                .putExtra(TourMapActivity.EXTRA_TOUR_REFERENCE, stored.reference)
            ActivityScenario.launch<TourMapActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val drawer = activity.findViewById<MaterialCardView>(R.id.dataPanel)
                    val behavior = BottomSheetBehavior.from(drawer)
                    assertTrue(behavior.isDraggable)
                    assertFalse(behavior.isHideable)
                    assertTrue(behavior.isFitToContents)
                    val basePeek = (48 * activity.resources.displayMetrics.density).toInt()
                    val baseContentBottomPadding = (12 * activity.resources.displayMetrics.density).toInt()
                    assertTrue(behavior.peekHeight >= basePeek)
                    assertEquals(
                        baseContentBottomPadding + behavior.peekHeight - basePeek,
                        activity.findViewById<View>(R.id.dataPanelContent).paddingBottom,
                    )
                    assertEquals(BottomSheetBehavior.STATE_EXPANDED, behavior.state)
                    activity.findViewById<android.view.View>(R.id.hideDataButton).performClick()
                }

                var state = BottomSheetBehavior.STATE_SETTLING
                for (attempt in 0 until 40) {
                    scenario.onActivity { activity ->
                        val drawer = activity.findViewById<MaterialCardView>(R.id.dataPanel)
                        state = BottomSheetBehavior.from(drawer).state
                    }
                    if (state == BottomSheetBehavior.STATE_COLLAPSED) break
                    SystemClock.sleep(25)
                }
                assertEquals(BottomSheetBehavior.STATE_COLLAPSED, state)

                scenario.onActivity { activity ->
                    val handleBounds = Rect()
                    assertTrue(
                        activity.findViewById<View>(R.id.hideDataButton)
                            .getGlobalVisibleRect(handleBounds),
                    )
                    assertTrue(handleBounds.height() > 0)
                }

                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.hideDataButton).performClick()
                }
                for (attempt in 0 until 40) {
                    scenario.onActivity { activity ->
                        val drawer = activity.findViewById<MaterialCardView>(R.id.dataPanel)
                        state = BottomSheetBehavior.from(drawer).state
                    }
                    if (state == BottomSheetBehavior.STATE_EXPANDED) break
                    SystemClock.sleep(25)
                }
                assertEquals(BottomSheetBehavior.STATE_EXPANDED, state)
            }
        } finally {
            store.deleteStoredTour(stored.reference)
        }
    }
}
