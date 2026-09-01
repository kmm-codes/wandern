package de.wandern.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.model.RecordingState
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.ActivityType
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackStoreRecoveryTest {
    @Test
    fun recordedTourCanBecomeLinkedRouteDefinitionWithoutLosingHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val recorded = store.saveRecordedTrack(
            GpxTrack(
                name = "Wiederholen-${System.nanoTime()}",
                segments = listOf(
                    listOf(
                        TrackPoint(48.0, 8.0, elevationMeters = 100.0, timeMillis = 1_000L),
                        TrackPoint(48.001, 8.0, elevationMeters = 110.0, timeMillis = 61_000L),
                    ),
                ),
                activityType = ActivityType.HIKING,
            ),
        )
        var planned: TrackStore.StoredTour? = null

        try {
            planned = store.saveRouteDefinitionFromRecording(recorded.reference)
            val route = store.loadStoredTrack(planned.reference)

            assertEquals(TrackStore.StoredTourOrigin.IMPORTED, planned.origin)
            assertEquals(TrackStore.PlannedTourSource.RECORDING, planned.plannedSource)
            assertEquals(recorded.reference, planned.sourceReference)
            assertEquals(2, route.points.size)
            assertTrue(route.points.all { it.timeMillis == null && it.speedMetersPerSecond == null })
            assertTrue(store.listStoredTours().any { it.reference == recorded.reference })
        } finally {
            planned?.let { store.deleteStoredTour(it.reference) }
            store.deleteStoredTour(recorded.reference)
        }
    }

    @Test
    fun routerPlannedTourKeepsItsOriginAfterStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val planned = store.saveImportedTrack(
            GpxTrack(
                name = "Router-${System.nanoTime()}",
                segments = listOf(
                    listOf(
                        TrackPoint(48.1, 8.1, elevationMeters = 100.0),
                        TrackPoint(48.2, 8.2, elevationMeters = 120.0),
                    ),
                ),
                activityType = ActivityType.CYCLING,
            ),
            plannedSource = TrackStore.PlannedTourSource.ROUTER,
        )

        try {
            val restored = TrackStore(context).listStoredTours().first { it.reference == planned.reference }
            assertEquals(TrackStore.PlannedTourSource.ROUTER, restored.plannedSource)
            assertEquals(ActivityType.CYCLING, restored.activityType)
        } finally {
            store.deleteStoredTour(planned.reference)
        }
    }

    @Test
    fun plannedTourCanBeUpdatedWithoutChangingItsReference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val start = TrackPoint(48.1, 8.1)
        val originalEnd = TrackPoint(48.2, 8.2)
        val planned = store.saveImportedTrack(
            GpxTrack("Vorher-${System.nanoTime()}", listOf(listOf(start, originalEnd))),
            plannedSource = TrackStore.PlannedTourSource.ROUTER,
            routeControlPoints = listOf(
                TrackStore.RouteControlPoint(start, "Start"),
                TrackStore.RouteControlPoint(originalEnd, "Altes Ziel"),
            ),
        )
        val via = TrackPoint(48.15, 8.25)
        val newEnd = TrackPoint(48.25, 8.3)

        try {
            val updated = store.updateImportedTrack(
                planned.reference,
                GpxTrack("Nachher", listOf(listOf(start, via, newEnd)), activityType = ActivityType.CYCLING),
                listOf(
                    TrackStore.RouteControlPoint(start, "Start"),
                    TrackStore.RouteControlPoint(via, "Zwischenziel"),
                    TrackStore.RouteControlPoint(newEnd, "Neues Ziel"),
                ),
            )

            assertEquals(planned.reference, updated.reference)
            assertEquals("Nachher", updated.name)
            assertEquals(ActivityType.CYCLING, updated.activityType)
            assertEquals(listOf(start, via, newEnd), store.loadStoredTrack(planned.reference).points)
            assertEquals(
                listOf("Start", "Zwischenziel", "Neues Ziel"),
                store.loadRouteControlPoints(planned.reference).map { it.label },
            )
        } finally {
            store.deleteStoredTour(planned.reference)
        }
    }

    @Test
    fun plannedTourCanBeDuplicatedWithControlPointsAndUniqueNames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TrackStore(context)
        val start = TrackPoint(48.1, 8.1)
        val end = TrackPoint(48.2, 8.2)
        val originalName = "Kopiertest-${System.nanoTime()}"
        val original = store.saveImportedTrack(
            GpxTrack(
                name = originalName,
                segments = listOf(listOf(start, end)),
                activityType = ActivityType.E_BIKE,
            ),
            plannedSource = TrackStore.PlannedTourSource.ROUTER,
            routeControlPoints = listOf(
                TrackStore.RouteControlPoint(start, "Startpunkt"),
                TrackStore.RouteControlPoint(end, "Zielpunkt"),
            ),
        )
        var firstCopy: TrackStore.StoredTour? = null
        var secondCopy: TrackStore.StoredTour? = null

        try {
            firstCopy = store.duplicateImportedTrack(original.reference)
            secondCopy = store.duplicateImportedTrack(firstCopy.reference)

            assertEquals("$originalName – Kopie", firstCopy.name)
            assertEquals("$originalName – Kopie 2", secondCopy.name)
            assertFalse(original.reference == firstCopy.reference)
            assertFalse(firstCopy.reference == secondCopy.reference)
            assertEquals(ActivityType.E_BIKE, firstCopy.activityType)
            assertEquals(TrackStore.PlannedTourSource.ROUTER, firstCopy.plannedSource)
            assertEquals(listOf(start, end), store.loadStoredTrack(firstCopy.reference).points)
            assertEquals(
                listOf("Startpunkt", "Zielpunkt"),
                store.loadRouteControlPoints(firstCopy.reference).map { it.label },
            )
        } finally {
            secondCopy?.let { store.deleteStoredTour(it.reference) }
            firstCopy?.let { store.deleteStoredTour(it.reference) }
            store.deleteStoredTour(original.reference)
        }
    }

    @Test
    fun pausedSessionSurvivesStoreRecreationAndCanBeDiscarded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstStore = TrackStore(context)
        discardActiveSessions(firstStore)
        val sessionId = firstStore.createSession(
            "Recovery-Test",
            routeReference = "imported:42",
            activityType = ActivityType.E_BIKE,
        )

        try {
            firstStore.appendPoint(
                sessionId,
                0,
                TrackPoint(48.0, 8.0, timeMillis = 1_000L, accuracyMeters = 5f),
            )
            firstStore.updateState(sessionId, RecordingState.PAUSED)

            val restoredStore = TrackStore(context)
            val restored = restoredStore.activeSession()

            assertEquals(sessionId, restored?.id)
            assertEquals(RecordingState.PAUSED, restored?.state)
            assertEquals("imported:42", restored?.routeReference)
            assertEquals(ActivityType.E_BIKE, restored?.activityType)
            assertEquals(ActivityType.E_BIKE, restoredStore.loadTrack(sessionId).activityType)
            assertEquals(1, restoredStore.loadTrack(sessionId).points.size)
            assertTrue(restoredStore.discardSession(sessionId))
            assertNull(restoredStore.activeSession())
            assertFalse(restoredStore.discardSession(sessionId))
        } finally {
            firstStore.discardSession(sessionId)
            discardActiveSessions(firstStore)
        }
    }

    private fun discardActiveSessions(store: TrackStore) {
        while (true) {
            val active = store.activeSession() ?: return
            store.discardSession(active.id)
        }
    }
}
