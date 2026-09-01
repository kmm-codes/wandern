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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugRecordingScenarioFlowTest {
    @Test
    fun collapsedScenarioKeepsPauseActionFullyVisible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-collapsed")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_COLLAPSED
            }
            scenario.onActivity { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                val collapsed = activity.findViewById<View>(
                    de.wandern.app.R.id.recordingCollapsedContent,
                )
                val pause = activity.findViewById<View>(de.wandern.app.R.id.recordingPauseButton)
                val visible = Rect()
                val behavior = BottomSheetBehavior.from(card)
                val fullyVisible = pause.getGlobalVisibleRect(visible) && visible.height() == pause.height
                assertTrue(
                    "pause action is clipped: visible=$visible pause=${pause.width}x${pause.height} " +
                        "sheetY=${card.y} collapsed=${collapsed.height} peek=${behavior.peekHeight}",
                    fullyVisible,
                )
            }
        }
    }

    @Test
    fun adbScenarioOpensStatsPageInContentSizedHalfState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-stats-medium")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_HALF_EXPANDED
            }
            scenario.onActivity { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                assertEquals(
                    1,
                    activity.findViewById<RecordingCarouselView>(
                        de.wandern.app.R.id.recordingInfoCarousel,
                    ).currentPage,
                )
                assertEquals(
                    View.INVISIBLE,
                    activity.findViewById<View>(de.wandern.app.R.id.recordingAdvancedActions).visibility,
                )
                assertFalse(
                    activity.findViewById<DrawerContentScrollView>(
                        de.wandern.app.R.id.recordingExpandedGroup,
                    ).contentScrollingEnabled,
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(de.wandern.app.R.id.recordingDetourFab).visibility,
                )
                assertMapFabAboveSheet(
                    activity.findViewById(de.wandern.app.R.id.centerButton),
                    card,
                    "centerButton",
                    maxGapDp = 32,
                )
                assertMapFabAboveSheet(
                    activity.findViewById(de.wandern.app.R.id.recordingDetourFab),
                    card,
                    "recordingDetourFab",
                )
            }
        }
    }

    @Test
    fun expandedScenarioShowsAdvancedActionsAndKeepsHandleOutsideScrollContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-paused-expanded")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_EXPANDED
            }
            scenario.onActivity { activity ->
                val header = activity.findViewById<View>(de.wandern.app.R.id.recordingCollapsedContent)
                val scroller = activity.findViewById<DrawerContentScrollView>(
                    de.wandern.app.R.id.recordingExpandedGroup,
                )
                assertEquals(View.VISIBLE, header.visibility)
                assertTrue(header.parent !== scroller)
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(de.wandern.app.R.id.recordingAdvancedActions).visibility,
                )
            }
        }
    }

    private fun assertMapFabAboveSheet(
        fab: View,
        sheet: View,
        label: String,
        maxGapDp: Int? = null,
    ) {
        val bounds = Rect()
        val sheetTop = sheet.y.toInt()
        assertTrue(
            "$label has no visible bounds: visibility=${fab.visibility} shown=${fab.isShown} " +
                "xy=${fab.x},${fab.y} size=${fab.width}x${fab.height} sheetTop=$sheetTop",
            fab.getGlobalVisibleRect(bounds),
        )
        assertTrue(
            "$label must remain above the sheet: fab=$bounds sheetTop=$sheetTop",
            bounds.bottom <= sheetTop,
        )
        maxGapDp?.let {
            val maxGapPx = (it * fab.resources.displayMetrics.density).toInt()
            assertTrue(
                "$label is detached from the sheet: fab=$bounds sheetTop=$sheetTop",
                sheetTop - bounds.bottom <= maxGapPx,
            )
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
        throw AssertionError("Debug-Szene wurde nicht innerhalb von 5 Sekunden dargestellt.")
    }
}
