package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
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
