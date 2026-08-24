package de.wandern.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.model.RecordingState
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
    fun pausedSessionSurvivesStoreRecreationAndCanBeDiscarded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstStore = TrackStore(context)
        val sessionId = firstStore.createSession("Recovery-Test", routeReference = "imported:42")

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
            assertEquals(1, restoredStore.loadTrack(sessionId).points.size)
            assertTrue(restoredStore.discardSession(sessionId))
            assertNull(restoredStore.activeSession())
            assertFalse(restoredStore.discardSession(sessionId))
        } finally {
            firstStore.discardSession(sessionId)
        }
    }
}
