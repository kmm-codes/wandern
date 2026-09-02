package de.wandern.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.model.ActivityType
import de.wandern.app.model.DetourRouteCandidate
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RouteAdjustmentKind
import de.wandern.app.model.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetourSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = DetourSessionStore(context)

    @After
    fun cleanUp() = store.clear(SESSION_ID)

    @Test
    fun persistsAndClearsActiveNavigationOverride() {
        val track = GpxTrack(
            name = "Umleitung",
            segments = listOf(
                listOf(
                    TrackPoint(48.0, 8.0),
                    TrackPoint(48.001, 8.002),
                    TrackPoint(48.002, 8.003),
                ),
            ),
            activityType = ActivityType.HIKING,
        )
        val candidate = DetourRouteCandidate(
            track = track,
            detourTrack = track.copy(segments = listOf(track.points.take(2))),
            departureDistanceMeters = 120.0,
            rejoinDistanceMeters = 900.0,
            skippedRouteMeters = 200.0,
            extraDistanceMeters = 350.0,
            directToDestination = false,
        )

        store.save(
            SESSION_ID,
            "planned:42",
            candidate,
            400.0,
            650.0,
            kind = RouteAdjustmentKind.REJOIN,
        )
        val restored = store.load(SESSION_ID)

        assertEquals("planned:42", restored?.originalRouteReference)
        assertEquals(false, restored?.restoresRecordingRoute)
        assertEquals(track.points.size, restored?.route?.points?.size)
        assertEquals(2, restored?.detourTrack?.points?.size)
        assertEquals(120.0, restored?.departureDistanceMeters ?: 0.0, 0.0)
        assertEquals(650.0, restored?.corridorEndMeters ?: 0.0, 0.0)
        assertEquals(RouteAdjustmentKind.REJOIN, restored?.kind)
        store.clear(SESSION_ID)
        assertNull(store.load(SESSION_ID))
    }

    @Test
    fun persistsDetourForRecordingOnlyRoute() {
        val track = GpxTrack(
            name = "Temporäre Route",
            segments = listOf(
                listOf(
                    TrackPoint(48.0, 8.0),
                    TrackPoint(48.002, 8.003),
                ),
            ),
            activityType = ActivityType.HIKING,
        )
        val candidate = DetourRouteCandidate(
            track = track,
            detourTrack = track,
            departureDistanceMeters = 80.0,
            rejoinDistanceMeters = 500.0,
            skippedRouteMeters = 150.0,
            extraDistanceMeters = 100.0,
            directToDestination = false,
        )

        store.save(
            sessionId = SESSION_ID,
            originalRouteReference = null,
            candidate = candidate,
            corridorStartMeters = 100.0,
            corridorEndMeters = 300.0,
            restoresRecordingRoute = true,
        )

        val restored = store.load(SESSION_ID)
        assertNull(restored?.originalRouteReference)
        assertEquals(true, restored?.restoresRecordingRoute)
        assertEquals(track.points.size, restored?.route?.points?.size)
    }

    private companion object {
        const val SESSION_ID = 9_876_543L
    }
}
