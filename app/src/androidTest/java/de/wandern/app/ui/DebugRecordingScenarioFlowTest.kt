package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertEquals
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
                val parent = card.parent as View
                assertTrue(behavior.isFitToContents)
                assertEquals(parent.paddingTop + parent.height - behavior.peekHeight, card.top)
                assertTrue(
                    "pause action is clipped: visible=$visible pause=${pause.width}x${pause.height} " +
                        "sheetY=${card.y} collapsed=${collapsed.height} peek=${behavior.peekHeight}",
                    fullyVisible,
                )
            }
        }
    }

    @Test
    fun firstExpansionShowsStatsAndAdvancedActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-expanded")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_EXPANDED
            }
            scenario.onActivity { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                val behavior = BottomSheetBehavior.from(card)
                val parent = card.parent as View
                assertTrue(behavior.isFitToContents)
                assertEquals(parent.paddingTop + parent.height - card.height, card.top)
                assertEquals(
                    0,
                    activity.findViewById<RecordingCarouselView>(
                        de.wandern.app.R.id.recordingInfoCarousel,
                    ).currentPage,
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(de.wandern.app.R.id.recordingAdvancedActions).visibility,
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
    fun recordingCarouselUsesSameShortSwipeThresholdInBothDirections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-expanded")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                activity.findViewById<RecordingCarouselView>(
                    de.wandern.app.R.id.recordingInfoCarousel,
                ).run { isShown && width > 0 && currentPage == 0 }
            }

            scenario.onActivity { activity ->
                val carousel = activity.findViewById<RecordingCarouselView>(
                    de.wandern.app.R.id.recordingInfoCarousel,
                )
                performChildStartedSwipe(carousel, startFraction = 0.62f, endFraction = 0.47f)
                assertEquals(1, carousel.currentPage)
                carousel.showPage(1, animate = false)

                // The chart consumes the initial touch itself. The carousel must still retain this
                // gesture's start point rather than the start point from the preceding left swipe.
                performChildStartedSwipe(carousel, startFraction = 0.38f, endFraction = 0.53f)
                assertEquals(0, carousel.currentPage)
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
                assertTrue(scroller.isNestedScrollingEnabled)
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(de.wandern.app.R.id.recordingAdvancedActions).visibility,
                )
            }
        }
    }

    @Test
    fun recordingDrawerKeepsOneHeightAcrossBothStableStates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-expanded")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_EXPANDED
            }
            var stableHeight = 0
            var collapsedPeekHeight = 0
            scenario.onActivity { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                stableHeight = card.height
                collapsedPeekHeight = BottomSheetBehavior.from(card).peekHeight
                activity.findViewById<View>(de.wandern.app.R.id.recordingPausedBanner).visibility =
                    View.VISIBLE
            }
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).peekHeight > collapsedPeekHeight
            }
            scenario.onActivity { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                assertEquals(stableHeight, card.height)
                BottomSheetBehavior.from(card).state = BottomSheetBehavior.STATE_COLLAPSED
            }
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_COLLAPSED
            }
            scenario.onActivity { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                assertEquals(stableHeight, card.height)
                BottomSheetBehavior.from(card).state = BottomSheetBehavior.STATE_EXPANDED
            }
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_EXPANDED
            }
            scenario.onActivity { activity ->
                assertEquals(
                    stableHeight,
                    activity.findViewById<View>(de.wandern.app.R.id.recordingCard).height,
                )
            }
        }
    }

    @Test
    fun finishDialogOffersDiscardNextToSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-paused-expanded")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                activity.findViewById<View>(de.wandern.app.R.id.recordingFinishButton).isShown
            }
            scenario.onActivity { activity ->
                activity.findViewById<View>(de.wandern.app.R.id.recordingFinishButton).performClick()
            }
            onView(withText(de.wandern.app.R.string.discard_recording)).check(matches(isDisplayed()))
            onView(withText(de.wandern.app.R.string.finish_and_save)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun freeRecordingShowsSetDestinationOnFirstExpansion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "free-expanded")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                val card = activity.findViewById<MaterialCardView>(de.wandern.app.R.id.recordingCard)
                BottomSheetBehavior.from(card).state == BottomSheetBehavior.STATE_EXPANDED
            }
            scenario.onActivity { activity ->
                val routeButton = activity.findViewById<MaterialButton>(
                    de.wandern.app.R.id.recordingRouteButton,
                )
                assertTrue(routeButton.isShown)
                assertEquals(
                    activity.getString(de.wandern.app.R.string.set_recording_destination),
                    routeButton.text.toString(),
                )
            }
        }
    }

    @Test
    fun navigationScenarioShowsTurnInstructionAboveMap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-navigation-collapsed")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                activity.findViewById<View>(de.wandern.app.R.id.navigationManeuverBanner).isShown
            }
            scenario.onActivity { activity ->
                val banner = activity.findViewById<View>(de.wandern.app.R.id.navigationManeuverBanner)
                val drawer = activity.findViewById<View>(de.wandern.app.R.id.recordingCard)
                val text = activity.findViewById<TextView>(de.wandern.app.R.id.navigationManeuverText)
                assertTrue(banner.isShown)
                assertTrue(banner.bottom < drawer.top)
                assertEquals(
                    activity.getString(
                        de.wandern.app.R.string.navigation_in_distance,
                        "85 m",
                        activity.getString(de.wandern.app.R.string.navigation_right),
                    ),
                    text.text.toString(),
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
        val sheetBounds = Rect()
        assertTrue(sheet.getGlobalVisibleRect(sheetBounds))
        val sheetTop = sheetBounds.top
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

    private fun performChildStartedSwipe(
        carousel: RecordingCarouselView,
        startFraction: Float,
        endFraction: Float,
    ) {
        val now = SystemClock.uptimeMillis()
        val startX = carousel.width * startFraction
        val endX = carousel.width * endFraction
        val y = carousel.height * 0.5f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, y, 0)
        val move = MotionEvent.obtain(now, now + 16L, MotionEvent.ACTION_MOVE, endX, y, 0)
        val up = MotionEvent.obtain(now, now + 32L, MotionEvent.ACTION_UP, endX, y, 0)
        try {
            carousel.onInterceptTouchEvent(down)
            carousel.onTouchEvent(move)
            carousel.onTouchEvent(up)
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
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
