package de.wandern.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.model.ActivityType
import de.wandern.app.model.DetourRouteCandidate
import de.wandern.app.model.GpxTrack
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
            rejoinDistanceMeters = 900.0,
            skippedRouteMeters = 200.0,
            extraDistanceMeters = 350.0,
            directToDestination = false,
        )

        store.save(SESSION_ID, "planned:42", candidate, 400.0, 650.0)
        val restored = store.load(SESSION_ID)

        assertEquals("planned:42", restored?.originalRouteReference)
        assertEquals(track.points.size, restored?.route?.points?.size)
        assertEquals(650.0, restored?.corridorEndMeters ?: 0.0, 0.0)
        store.clear(SESSION_ID)
        assertNull(store.load(SESSION_ID))
    }

    private companion object {
        const val SESSION_ID = 9_876_543L
    }
}
