package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RouteVariantPolicy
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutePlannerFlowTest {
    @Test
    fun opensReadyForStartAndRequiresTwoPoints() {
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.planner_choose_start),
                    activity.findViewById<TextView>(R.id.instructionText).text.toString(),
                )
                assertFalse(activity.findViewById<MaterialButton>(R.id.calculateButton).isEnabled)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.calculateButton).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.startPointButton).visibility)
                assertTrue(activity.findViewById<MaterialButton>(R.id.destinationPointButton).isEnabled)
                assertEquals(
                    activity.getString(R.string.choose_destination),
                    activity.findViewById<MaterialButton>(R.id.destinationPointButton).text.toString(),
                )
                assertFalse(activity.findViewById<View>(R.id.swapEndpointsButton).isEnabled)
                val undoItem = activity
                    .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    .menu
                    .findItem(R.id.action_undo_route_edit)
                assertFalse(undoItem.isVisible)
                assertEquals(activity.getString(R.string.undo), undoItem.tooltipText)
            }
        }
    }

    @Test
    fun destinationCanBeChosenBeforeStart() {
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<MaterialButton>(R.id.destinationPointButton).performClick()

                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pointSearchOverlay).visibility)
                assertEquals(true, activity.findViewById<EditText>(R.id.placeSearchInput).hasFocus())
            }
        }
    }

    @Test
    fun aSingleEndpointCanBeMovedBetweenStartAndDestination() {
        val point = TrackPoint(48.99, 8.40)
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.privateField<MutableList<TrackPoint>>("waypoints") += point
                activity.invokePrivate("renderPlannerState")

                val swap = activity.findViewById<MaterialButton>(R.id.swapEndpointsButton)
                assertEquals(View.VISIBLE, swap.visibility)
                assertTrue(swap.isEnabled)
                assertEquals(
                    activity.getString(R.string.move_start_to_destination),
                    swap.text.toString(),
                )
                assertEquals(
                    activity.getString(R.string.move_start_to_destination),
                    swap.contentDescription,
                )

                swap.performClick()

                assertTrue(activity.privateField<List<TrackPoint>>("waypoints").isEmpty())
                assertEquals(point, activity.privateField<TrackPoint?>("destinationDraft"))
                assertEquals(
                    activity.getString(R.string.move_destination_to_start),
                    swap.contentDescription,
                )
                assertEquals(
                    activity.getString(R.string.move_destination_to_start),
                    swap.text.toString(),
                )

                swap.performClick()

                assertEquals(listOf(point), activity.privateField<List<TrackPoint>>("waypoints"))
                assertEquals(null, activity.privateField<TrackPoint?>("destinationDraft"))
            }
        }
    }

    @Test
    fun tappingStartOpensLiveSearchWithPointActions() {
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<MaterialButton>(R.id.startPointButton).performClick()

                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pointSearchOverlay).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.plannerCard).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.useCurrentPositionButton).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.useHomeButton).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.selectPointOnMapButton).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.deletePointButton).visibility)
                assertEquals(true, activity.findViewById<EditText>(R.id.placeSearchInput).hasFocus())

                activity.findViewById<View>(R.id.closePointSearchButton).performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.pointSearchOverlay).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.plannerCard).visibility)
            }
        }
    }

    @Test
    fun waypointEditorCannotBeCollapsedBeforeThereIsAnIntermediatePoint() {
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val editor = activity.findViewById<View>(R.id.pointEditor)

                assertEquals(View.VISIBLE, editor.visibility)
                assertEquals(null, activity.findViewById<View?>(R.id.waypointExpandButton))
                val drawer = activity.findViewById<MaterialCardView>(R.id.plannerCard)
                val behavior = BottomSheetBehavior.from(drawer)
                assertEquals(BottomSheetBehavior.STATE_EXPANDED, behavior.state)
                assertTrue(behavior.isDraggable)
                assertEquals(0, (drawer.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin)
                assertTrue(
                    behavior.peekHeight >= (56 * activity.resources.displayMetrics.density).toInt(),
                )
                val root = activity.findViewById<View>(R.id.root)
                val toolbar = activity.findViewById<View>(R.id.toolbar)
                val content = activity.findViewById<android.view.ViewGroup>(R.id.plannerContent)
                val header = activity.findViewById<View>(R.id.drawerCompactHeader)
                val lastVisibleChildBottom = (0 until content.childCount)
                    .map(content::getChildAt)
                    .filter { it.visibility != View.GONE }
                    .maxOfOrNull { it.bottom }
                    ?: 0
                val naturalContentHeight = lastVisibleChildBottom + content.paddingBottom
                val expectedVisibleHeight = (header.height + naturalContentHeight).coerceIn(
                    behavior.peekHeight,
                    root.height - toolbar.bottom,
                )
                assertEquals(expectedVisibleHeight, drawer.height)
                assertEquals(root.height - expectedVisibleHeight, behavior.expandedOffset)
                assertEquals(
                    activity.getString(R.string.planner_compact_empty),
                    activity.findViewById<TextView>(R.id.plannerCompactSummaryText).text.toString(),
                )
                assertEquals(BottomSheetBehavior.STATE_EXPANDED, behavior.state)
            }
        }
    }

    @Test
    fun plannerDrawerConsumesTouchesOnNonInteractiveSurface() {
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val drawer = activity.findViewById<MaterialCardView>(R.id.plannerCard)
                val header = activity.findViewById<View>(R.id.drawerCompactHeader)
                val eventTime = SystemClock.uptimeMillis()
                val x = drawer.width / 2f
                val y = header.height + 1f
                val down = MotionEvent.obtain(
                    eventTime,
                    eventTime,
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    0,
                )
                val up = MotionEvent.obtain(
                    eventTime,
                    eventTime + 16L,
                    MotionEvent.ACTION_UP,
                    x,
                    y,
                    0,
                )

                assertTrue(drawer.isClickable)
                assertTrue(drawer.dispatchTouchEvent(down))
                assertTrue(drawer.dispatchTouchEvent(up))

                down.recycle()
                up.recycle()
            }
        }
    }

    @Test
    fun plannerDrawerOperationLockFreezesItsExtentAndDragging() {
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val drawer = activity.findViewById<MaterialCardView>(R.id.plannerCard)
                val behavior = BottomSheetBehavior.from(drawer)
                val overlay = activity.findViewById<View>(R.id.plannerLoadingOverlay)
                val instruction = activity.findViewById<View>(R.id.instructionRow)
                val initialHeight = drawer.height

                activity.invokePrivate("lockPlannerDrawerForOperation")

                assertEquals(View.VISIBLE, overlay.visibility)
                assertTrue(overlay.isClickable)
                assertFalse(behavior.isDraggable)
                assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, overlay.layoutParams.width)
                assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, overlay.layoutParams.height)

                instruction.visibility = View.GONE
                activity.invokePrivate("updatePlannerExtent")
                assertEquals(initialHeight, drawer.height)

                instruction.visibility = View.VISIBLE
                activity.invokePrivate("unlockPlannerDrawerAfterOperation")
                assertEquals(View.GONE, overlay.visibility)
                assertTrue(behavior.isDraggable)
            }
        }
    }

    @Test
    fun plannerDrawerKeepsItsContentAboveTheSystemNavigationArea() {
        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(R.id.root)
                val content = activity.findViewById<View>(R.id.plannerContent)
                val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.plannerScroll)
                val systemBottom = ViewCompat.getRootWindowInsets(root)
                    ?.getInsets(WindowInsetsCompat.Type.systemBars())
                    ?.bottom
                    ?: 0

                assertTrue(content.paddingBottom >= systemBottom)
                assertTrue(content.height <= scroll.height || scroll.canScrollVertically(1))
            }
        }
    }

    @Test
    fun removingAnIntermediatePointKeepsTheWaypointEditorExpandedAfterRerouting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val start = TrackPoint(48.0, 8.0)
        val firstVia = TrackPoint(48.006, 8.012)
        val secondVia = TrackPoint(48.014, 8.018)
        val end = TrackPoint(48.02, 8.0)
        val stored = store.saveImportedTrack(
            GpxTrack(
                "Editierbar-${System.nanoTime()}",
                listOf(listOf(start, firstVia, secondVia, end)),
            ),
            plannedSource = TrackStore.PlannedTourSource.ROUTER,
            routeControlPoints = listOf(
                TrackStore.RouteControlPoint(start, "Start"),
                TrackStore.RouteControlPoint(firstVia, "Mitte 1"),
                TrackStore.RouteControlPoint(secondVia, "Mitte 2"),
                TrackStore.RouteControlPoint(end, "Ziel"),
            ),
        )

        try {
            val intent = Intent(context, RoutePlannerActivity::class.java)
                .putExtra(RoutePlannerActivity.EXTRA_EDIT_TOUR_REFERENCE, stored.reference)
            ActivityScenario.launch<RoutePlannerActivity>(intent).use { scenario ->
                var loaded = false
                for (attempt in 0 until 60) {
                    scenario.onActivity { activity ->
                        loaded = activity.findViewById<View>(R.id.waypointList).visibility == View.VISIBLE &&
                            activity.titleOrToolbarTitle() == activity.getString(R.string.edit_tour)
                    }
                    if (loaded) break
                    SystemClock.sleep(50)
                }
                scenario.onActivity { activity ->
                    assertTrue(loaded)
                    assertEquals(activity.getString(R.string.edit_tour), activity.titleOrToolbarTitle())
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.waypointList).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.routeChoiceCard).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.instructionText).visibility)
                    assertFalse(activity.saveRouteMenuItem().isVisible)
                    val pointEditor = activity.findViewById<View>(R.id.pointEditor)
                    val waypointList = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                        R.id.waypointList,
                    )
                    val firstViaRow = checkNotNull(waypointList.findViewHolderForAdapterPosition(1))
                    val waypointExpand = firstViaRow.itemView.findViewById<View>(R.id.waypointExpandButton)
                    assertEquals(View.VISIBLE, pointEditor.visibility)
                    assertEquals(View.VISIBLE, waypointExpand.visibility)
                    waypointExpand.performClick()
                    assertEquals(View.VISIBLE, pointEditor.visibility)
                    assertEquals(3, waypointList.adapter?.itemCount)
                    val summaryRow = checkNotNull(waypointList.findViewHolderForAdapterPosition(1))
                    assertEquals(
                        "2 Zwischenziele",
                        summaryRow.itemView.findViewById<MaterialButton>(R.id.waypointButton).text.toString(),
                    )
                    summaryRow.itemView.findViewById<View>(R.id.waypointExpandButton).performClick()
                    assertEquals(4, waypointList.adapter?.itemCount)
                    val viaRow = checkNotNull(waypointList.findViewHolderForAdapterPosition(1))
                    val deleteVia = viaRow.itemView.findViewById<View>(R.id.deleteWaypointButton)
                    assertEquals(View.VISIBLE, deleteVia.visibility)
                    deleteVia.performClick()
                    assertEquals(3, waypointList.adapter?.itemCount)
                    assertEquals(View.VISIBLE, pointEditor.visibility)

                    val routeSelection = RoutePlannerActivity::class.java.getDeclaredMethod(
                        "enterRouteSelectionMode",
                        List::class.java,
                    ).apply { isAccessible = true }
                    routeSelection.invoke(
                        activity,
                        listOf(
                            GpxTrack("Variante 1", listOf(listOf(start, secondVia, end))),
                            GpxTrack("Variante 2", listOf(listOf(start, secondVia, end))),
                        ),
                    )
                    RoutePlannerActivity::class.java.getDeclaredMethod("renderPlannerState")
                        .apply { isAccessible = true }
                        .invoke(activity)

                    assertEquals(View.VISIBLE, pointEditor.visibility)
                }
            }
        } finally {
            store.deleteStoredTour(stored.reference)
        }
    }

    @Test
    fun reversingAnExistingClosedRouteKeepsItsSemanticStartAndDestination() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val start = TrackPoint(49.0, 8.0)
        val north = TrackPoint(49.01, 8.0)
        val east = TrackPoint(49.01, 8.015)
        val south = TrackPoint(49.0, 8.015)
        val track = GpxTrack("Geschlossener Rundweg", listOf(listOf(start, north, east, south, start)))
        val stored = store.saveImportedTrack(
            track,
            plannedSource = TrackStore.PlannedTourSource.ROUTER,
            // Simulates the short-lived format that stored extracted loop geometry
            // instead of the user's original planning points.
            routeControlPoints = listOf(
                TrackStore.RouteControlPoint(start, "Start"),
                TrackStore.RouteControlPoint(north),
                TrackStore.RouteControlPoint(east, "Wendepunkt-Ziel"),
                TrackStore.RouteControlPoint(south),
                TrackStore.RouteControlPoint(start, "Start"),
            ),
        )

        try {
            val intent = Intent(context, RoutePlannerActivity::class.java)
                .putExtra(RoutePlannerActivity.EXTRA_EDIT_TOUR_REFERENCE, stored.reference)
            ActivityScenario.launch<RoutePlannerActivity>(intent).use { scenario ->
                var ready = false
                for (attempt in 0 until 60) {
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.waypointList)
                        ready = list.adapter?.itemCount == 2
                    }
                    if (ready) break
                    SystemClock.sleep(50)
                }

                scenario.onActivity { activity ->
                    assertTrue(ready)
                    val summary = activity.findViewById<TextView>(R.id.plannerCompactSummaryText)
                    val distanceBefore = summary.text.toString()
                    val waypointList = activity
                        .findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.waypointList)
                    val startLabel = checkNotNull(
                        waypointList.findViewHolderForAdapterPosition(0),
                    ).itemView.findViewById<MaterialButton>(R.id.waypointButton).text.toString()
                    val destinationLabel = checkNotNull(
                        waypointList.findViewHolderForAdapterPosition(1),
                    ).itemView.findViewById<MaterialButton>(R.id.waypointButton).text.toString()
                    assertEquals(activity.getString(R.string.planner_start_value, "Start"), startLabel)
                    assertEquals(
                        activity.getString(R.string.planner_destination_value, "Wendepunkt-Ziel"),
                        destinationLabel,
                    )

                    activity.findViewById<View>(R.id.swapEndpointsButton).performClick()

                    assertEquals(distanceBefore, summary.text.toString())
                    assertEquals(2, waypointList.adapter?.itemCount)
                    @Suppress("UNCHECKED_CAST")
                    val persistedControls = RoutePlannerActivity::class.java
                        .getDeclaredMethod("currentSemanticControlPoints")
                        .apply { isAccessible = true }
                        .invoke(activity) as List<TrackStore.RouteControlPoint>
                    assertEquals(listOf("Start", "Wendepunkt-Ziel"), persistedControls.map { it.label })
                    assertTrue(activity.saveRouteMenuItem().isVisible)
                }
            }
        } finally {
            store.deleteStoredTour(stored.reference)
        }
    }

    @Test
    fun reversingANewCompletedRoundTripKeepsTheChosenGeometryAndUndoRestoresIt() {
        val start = TrackPoint(49.0, 8.0)
        val outbound = TrackPoint(49.01, 8.0)
        val turningPoint = TrackPoint(49.012, 8.015)
        val inbound = TrackPoint(49.0, 8.015)
        val loop = GpxTrack(
            "Rundweg",
            listOf(listOf(start, outbound, turningPoint, inbound, start)),
        )

        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.privateField<MutableList<TrackPoint>>("waypoints") +=
                    listOf(start, turningPoint)
                activity.setPrivateEnum("routeMode", "ROUND_TRIP")
                activity.setPrivateEnum("roundTripPhase", "COMPLETE")
                activity.setPrivateField("calculatedRoute", loop)
                activity.setPrivateField("routeAlternatives", listOf(loop))
                activity.invokePrivate("renderPlannerState")

                activity.findViewById<View>(R.id.swapEndpointsButton).performClick()

                val reversed = activity.privateField<GpxTrack>("calculatedRoute")
                assertEquals(RouteVariantPolicy.reversed(loop).points, reversed.points)
                assertEquals("NONE", activity.privateField<Enum<*>>("roundTripPhase").name)
                assertTrue(activity.saveRouteMenuItem().isVisible)

                activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                    .menu
                    .performIdentifierAction(R.id.action_undo_route_edit, 0)

                assertEquals(loop.points, activity.privateField<GpxTrack>("calculatedRoute").points)
                assertEquals("COMPLETE", activity.privateField<Enum<*>>("roundTripPhase").name)
            }
        }
    }

    @Test
    fun roundTripRequiresExplicitReturnSelectionBeforeSaving() {
        val outboundAndReturn = GpxTrack(
            "Rundweg 1",
            listOf(
                listOf(
                    TrackPoint(49.0, 8.0),
                    TrackPoint(49.01, 8.01),
                    TrackPoint(49.0, 8.0),
                ),
            ),
        )
        val alternative = outboundAndReturn.copy(name = "Rundweg 2")

        ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setPrivateEnum("routeMode", "ROUND_TRIP")
                activity.setPrivateEnum("roundTripPhase", "RETURN")
                activity.setPrivateField("calculatedRoute", outboundAndReturn)
                activity.setPrivateField("routeAlternatives", listOf(outboundAndReturn, alternative))
                activity.invokePrivate("renderPlannerState")

                val selectReturn = activity.findViewById<MaterialButton>(R.id.calculateButton)
                val save = activity.saveRouteMenuItem()
                assertEquals(View.VISIBLE, selectReturn.visibility)
                assertEquals(activity.getString(R.string.select_return_route), selectReturn.text.toString())
                assertFalse(save.isVisible)

                selectReturn.performClick()

                assertEquals(View.GONE, selectReturn.visibility)
                assertTrue(save.isVisible)
                assertEquals(
                    activity.getString(R.string.planner_round_trip_ready),
                    activity.findViewById<TextView>(R.id.routeChoiceTitle).text.toString(),
                )

                activity.findViewById<View>(R.id.nextAlternativeButton).performClick()

                assertEquals(View.VISIBLE, selectReturn.visibility)
                assertFalse(save.isVisible)
            }
        }
    }

    @Test
    fun savedRoutePreviewReturnsToTourLibraryOnAndroidBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val libraryMonitor = instrumentation.addMonitor(
            TourLibraryActivity::class.java.name,
            null,
            false,
        )
        val previewMonitor = instrumentation.addMonitor(
            MainActivity::class.java.name,
            null,
            false,
        )
        try {
            ActivityScenario.launch(RoutePlannerActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    RoutePlannerActivity::class.java.getDeclaredMethod(
                        "openSavedRoutePreview",
                        String::class.java,
                    ).apply { isAccessible = true }
                        .invoke(activity, "imported:missing-navigation-test")
                    activity.finish()
                }

                val preview = checkNotNull(
                    instrumentation.waitForMonitorWithTimeout(previewMonitor, 5_000),
                ) as MainActivity
                instrumentation.runOnMainSync {
                    preview.onBackPressedDispatcher.onBackPressed()
                }
                val library = checkNotNull(
                    instrumentation.waitForMonitorWithTimeout(libraryMonitor, 5_000),
                )

                var libraryResumed = false
                for (attempt in 0 until 50) {
                    instrumentation.runOnMainSync {
                        libraryResumed = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .contains(library)
                    }
                    if (libraryResumed) break
                    SystemClock.sleep(50)
                }
                assertTrue(libraryResumed)
                instrumentation.runOnMainSync { library.finish() }
            }
        } finally {
            instrumentation.removeMonitor(libraryMonitor)
            instrumentation.removeMonitor(previewMonitor)
        }
    }

    private fun RoutePlannerActivity.titleOrToolbarTitle(): String =
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title.toString()

    private fun RoutePlannerActivity.saveRouteMenuItem() =
        checkNotNull(
            findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                .menu
                .findItem(R.id.action_save_route),
        )

    private fun RoutePlannerActivity.setPrivateField(name: String, value: Any?) {
        RoutePlannerActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> RoutePlannerActivity.privateField(name: String): T =
        RoutePlannerActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this) as T

    private fun RoutePlannerActivity.setPrivateEnum(name: String, constantName: String) {
        val field = RoutePlannerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val constant = checkNotNull(field.type.enumConstants)
            .single { (it as Enum<*>).name == constantName }
        field.set(this, constant)
    }

    private fun RoutePlannerActivity.invokePrivate(name: String) {
        RoutePlannerActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(this)
    }
}
