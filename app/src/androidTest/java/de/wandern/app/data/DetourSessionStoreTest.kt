package de.wandern.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.model.ActivityType
import de.wandern.app.model.DetourRouteCandidate
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RouteAdjustmentKind
import de.wandern.app.model.RouteClosure
import de.wandern.app.model.TrackPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun keepsClosuresForTheWholeSessionAndClearsThemWithTheDetour() {
        val firstClosure = RouteClosure(
            id = 1L,
            createdAtMillis = 1_700_000_000_000L,
            widthMeters = 30,
            points = listOf(TrackPoint(48.0, 8.0), TrackPoint(48.0, 8.002)),
        )
        val secondClosure = RouteClosure(
            id = 2L,
            createdAtMillis = 1_700_000_060_000L,
            widthMeters = 45,
            points = listOf(TrackPoint(48.01, 8.01, elevationMeters = 320.0), TrackPoint(48.012, 8.012)),
        )

        store.addClosure(SESSION_ID, firstClosure)
        store.addClosure(SESSION_ID, secondClosure)
        val restored = store.closures(SESSION_ID)

        assertEquals(2, restored.size)
        assertEquals(listOf(1L, 2L), restored.map { it.id })
        assertEquals(1_700_000_060_000L, restored.last().createdAtMillis)
        assertEquals(45, restored.last().widthMeters)
        assertEquals(320.0, restored.last().points.first().elevationMeters ?: 0.0, 0.001)
        assertEquals(8.002, restored.first().points.last().longitude, 0.000001)
        assertTrue(restored.first().noGoPoints.isNotEmpty())

        store.clear(SESSION_ID)
        assertTrue(store.closures(SESSION_ID).isEmpty())
    }

    @Test
    fun aRouteChangeDropsTheDetourAndKeepsTheClosures() {
        val track = GpxTrack(
            name = "Umleitung",
            segments = listOf(listOf(TrackPoint(48.0, 8.0), TrackPoint(48.002, 8.003))),
            activityType = ActivityType.HIKING,
        )
        val closure = RouteClosure(
            id = 3L,
            createdAtMillis = 1_700_000_000_000L,
            widthMeters = 30,
            points = listOf(TrackPoint(48.0, 8.0), TrackPoint(48.0, 8.002)),
        )
        store.save(
            sessionId = SESSION_ID,
            originalRouteReference = "planned:42",
            candidate = DetourRouteCandidate(
                track = track,
                detourTrack = track,
                departureDistanceMeters = 80.0,
                rejoinDistanceMeters = 500.0,
                skippedRouteMeters = 150.0,
                extraDistanceMeters = 100.0,
                directToDestination = false,
            ),
            corridorStartMeters = 100.0,
            corridorEndMeters = 300.0,
        )
        store.addClosure(SESSION_ID, closure)

        store.clearDetour(SESSION_ID)

        assertNull(store.load(SESSION_ID))
        assertEquals(listOf(3L), store.closures(SESSION_ID).map { it.id })

        store.clear(SESSION_ID)
        assertTrue(store.closures(SESSION_ID).isEmpty())
    }

    @Test
    fun replacesAClosureThatIsAddedTwice() {
        val closure = RouteClosure(
            id = 7L,
            createdAtMillis = 1_700_000_000_000L,
            widthMeters = 30,
            points = listOf(TrackPoint(48.0, 8.0), TrackPoint(48.0, 8.002)),
        )

        store.addClosure(SESSION_ID, closure)
        val updated = store.addClosure(SESSION_ID, closure.copy(widthMeters = 60))

        assertEquals(1, updated.size)
        assertEquals(60, store.closures(SESSION_ID).single().widthMeters)
    }

    private companion object {
        const val SESSION_ID = 9_876_543L
    }
}
