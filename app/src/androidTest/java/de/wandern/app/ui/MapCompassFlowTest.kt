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
import de.wandern.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapCompassFlowTest {
    @Test
    fun compassButtonSitsBesideThePlanningBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntil(scenario) { activity ->
                activity.findViewById<View>(R.id.compassFab).isShown &&
                    activity.findViewById<View>(R.id.planningBar).isShown
            }
            scenario.onActivity { activity ->
                val compass = bounds(activity.findViewById(R.id.compassFab), "compassFab")
                val planningBar = bounds(activity.findViewById(R.id.planningBar), "planningBar")
                assertFalse(
                    "compass overlaps the planning bar: compass=$compass bar=$planningBar",
                    Rect.intersects(compass, planningBar),
                )
                assertTrue(
                    "compass must stay right of the planning bar: compass=$compass bar=$planningBar",
                    compass.left >= planningBar.right,
                )
                assertTrue(
                    "compass must share the top row with the planning bar: " +
                        "compass=$compass bar=$planningBar",
                    compass.top < planningBar.bottom && compass.bottom > planningBar.top,
                )
            }
        }
    }

    @Test
    fun compassButtonLeavesRoomForTheNavigationBanner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-navigation-collapsed")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitUntil(scenario) { activity ->
                activity.findViewById<View>(R.id.navigationManeuverBanner).isShown &&
                    activity.findViewById<View>(R.id.compassFab).isShown
            }
            scenario.onActivity { activity ->
                val compass = bounds(activity.findViewById(R.id.compassFab), "compassFab")
                val banner = bounds(
                    activity.findViewById(R.id.navigationManeuverBanner),
                    "navigationManeuverBanner",
                )
                val map = bounds(activity.findViewById(R.id.mapView), "mapView")
                assertFalse(
                    "compass overlaps the navigation banner: compass=$compass banner=$banner",
                    Rect.intersects(compass, banner),
                )
                assertTrue(
                    "compass must stay right of the navigation banner: " +
                        "compass=$compass banner=$banner",
                    compass.left >= banner.right,
                )
                assertTrue(
                    "compass must sit in the top right corner: compass=$compass map=$map",
                    compass.right <= map.right && compass.top < map.top + map.height() / 3,
                )
            }
        }
    }

    /**
     * All three scenes run on the same activity on purpose: giving the corner its room back is
     * what has to be tested, and further launches would only add maps to an already long suite.
     *
     * The free scene is the close call. Its two button stack ends up a hair under the compass and
     * is carried clear by the shift alone, so it guards the compass against a rule that gives up
     * on anything short of the full gap.
     */
    @Test
    fun compassLeavesTheCornerToTheStackOnlyWhileTheStackNeedsIt() {
        launchScene("route-expanded") { scenario ->
            awaitDrawer(scenario, BottomSheetBehavior.STATE_EXPANDED)
            awaitSettledCorner(scenario)
            scenario.onActivity { activity ->
                assertEquals(
                    "the route scene must put the detour button into the stack",
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.recordingDetourFab).visibility,
                )
                assertCompassKeepsOutOfTheStack(activity)
            }

            // MainActivity is singleTop, so the next scene reaches the same instance.
            switchScene(scenario, "route-collapsed", BottomSheetBehavior.STATE_COLLAPSED)
            scenario.onActivity { activity ->
                assertCompassIsOnScreen(activity, "the collapsed drawer leaves the corner free")
            }

            switchScene(scenario, "free-expanded", BottomSheetBehavior.STATE_EXPANDED)
            scenario.onActivity { activity ->
                assertEquals(
                    "the free scene must drop the detour button from the stack",
                    View.GONE,
                    activity.findViewById<View>(R.id.recordingDetourFab).visibility,
                )
                assertCompassIsOnScreen(activity, "a two button stack still clears the compass")
            }
        }
    }

    private fun assertCompassIsOnScreen(activity: MainActivity, because: String) {
        val compass = activity.findViewById<View>(R.id.compassFab)
        assertEquals(
            "$because, so the compass must stay: " + cornerReport(activity),
            View.VISIBLE,
            compass.visibility,
        )
        assertTrue("compass is not on screen: " + cornerReport(activity), compass.isShown)
        assertCompassKeepsOutOfTheStack(activity)
    }

    /** The compass either yields its slot or keeps clear of every button of the stack. */
    private fun assertCompassKeepsOutOfTheStack(activity: MainActivity) {
        val compass = activity.findViewById<View>(R.id.compassFab)
        if (compass.visibility != View.VISIBLE) return
        val compassBounds = bounds(compass, "compassFab")
        stackButtons(activity).forEach { (label, view) ->
            if (view.visibility != View.VISIBLE) return@forEach
            val fab = bounds(view, label)
            assertFalse(
                "visible compass overlaps $label: compass=$compassBounds $label=$fab · " +
                    cornerReport(activity),
                Rect.intersects(compassBounds, fab),
            )
        }
    }

    private fun stackButtons(activity: MainActivity): List<Pair<String, View>> = listOf(
        "recordingDetourFab" to activity.findViewById<View>(R.id.recordingDetourFab),
        "mapSettingsFab" to activity.findViewById<View>(R.id.mapSettingsFab),
        "centerButton" to activity.findViewById<View>(R.id.centerButton),
    )

    private fun cornerReport(activity: MainActivity): String =
        (listOf("compassFab" to activity.findViewById<View>(R.id.compassFab)) + stackButtons(activity))
            .joinToString(" ") { (label, view) ->
                "$label[visibility=${view.visibility} y=${view.y} h=${view.height}]"
            }

    private fun launchScene(scene: String, block: (ActivityScenario<MainActivity>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<MainActivity>(
            sceneIntent(context, scene).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use(block)
    }

    private fun sceneIntent(context: Context, scene: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, scene)

    private fun switchScene(
        scenario: ActivityScenario<MainActivity>,
        scene: String,
        drawerState: Int,
    ) {
        scenario.onActivity { activity -> activity.startActivity(sceneIntent(activity, scene)) }
        awaitDrawer(scenario, drawerState)
        awaitSettledCorner(scenario)
    }

    private fun awaitDrawer(scenario: ActivityScenario<MainActivity>, state: Int) {
        waitUntil(scenario) { activity ->
            val drawer = activity.findViewById<View>(R.id.recordingCard)
            activity.findViewById<View>(R.id.centerButton).isShown &&
                drawer.isShown &&
                BottomSheetBehavior.from(drawer).state == state
        }
    }

    /** The stack is placed before the next draw, so the corner is read once it stops moving. */
    private fun awaitSettledCorner(scenario: ActivityScenario<MainActivity>) {
        var previous = ""
        repeat(50) {
            var current = ""
            scenario.onActivity { current = cornerReport(it) }
            if (current.isNotEmpty() && current == previous) return
            previous = current
            SystemClock.sleep(100)
        }
        throw AssertionError("Die Ecke kam nicht innerhalb von 5 Sekunden zur Ruhe: $previous")
    }

    private fun bounds(view: View, label: String): Rect {
        val rect = Rect()
        assertTrue(
            "$label has no visible bounds: visibility=${view.visibility} shown=${view.isShown}",
            view.getGlobalVisibleRect(rect),
        )
        return rect
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
        throw AssertionError("Kartenansicht wurde nicht innerhalb von 5 Sekunden dargestellt.")
    }
}
