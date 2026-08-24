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
