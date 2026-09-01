package de.wandern.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRouteStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = RecordingRouteStore(context)

    @After
    fun cleanUp() = store.clear(SESSION_ID)

    @Test
    fun persistsControlPointsWithoutChangingTheSavedTour() {
        val start = TrackPoint(48.99, 8.40)
        val destination = TrackPoint(49.01, 8.43)
        val trackStore = TrackStore(context)
        val originalTour = trackStore.saveImportedTrack(
            GpxTrack(
                name = "Original saved tour",
                segments = listOf(listOf(start, destination)),
                activityType = ActivityType.HIKING,
            ),
        )
        val route = GpxTrack(
            name = "Only for this recording",
            segments = listOf(listOf(start, TrackPoint(49.0, 8.42), destination)),
            activityType = ActivityType.HIKING,
        )
        val controls = listOf(
            TrackStore.RouteControlPoint(start, "Current position"),
            TrackStore.RouteControlPoint(destination, "Destination"),
        )
        try {
            store.save(SESSION_ID, route, controls)
            val restored = store.load(SESSION_ID)

            assertEquals(route.points, restored?.route?.points)
            assertEquals(controls, restored?.controlPoints)
            assertEquals(listOf(start, destination), trackStore.loadStoredTrack(originalTour.reference).points)
            store.clear(SESSION_ID)
            assertNull(store.load(SESSION_ID))
        } finally {
            trackStore.deleteStoredTour(originalTour.reference)
        }
    }

    private companion object {
        const val SESSION_ID = 9_876_544L
    }
}
