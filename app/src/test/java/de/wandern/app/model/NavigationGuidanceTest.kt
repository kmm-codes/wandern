package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuidanceTest {
    @Test
    fun `generates right turn and arrival from route geometry`() {
        val route = GpxTrack(
            name = "Corner",
            segments = listOf(
                listOf(
                    TrackPoint(49.0000, 8.0000),
                    TrackPoint(49.0002, 8.0000),
                    TrackPoint(49.0004, 8.0000),
                    TrackPoint(49.0004, 8.0003),
                    TrackPoint(49.0004, 8.0006),
                ),
            ),
        )

        val maneuvers = NavigationManeuverGenerator.generate(route)

        assertEquals(NavigationManeuverType.RIGHT, maneuvers.first().type)
        assertEquals(NavigationManeuverType.ARRIVE, maneuvers.last().type)
        assertTrue(maneuvers.first().distanceAlongRouteMeters < maneuvers.last().distanceAlongRouteMeters)
    }

    @Test
    fun `does not announce gentle bends`() {
        val route = GpxTrack(
            name = "Gentle",
            segments = listOf(
                listOf(
                    TrackPoint(49.0000, 8.0000),
                    TrackPoint(49.0002, 8.00002),
                    TrackPoint(49.0004, 8.00006),
                    TrackPoint(49.0006, 8.00012),
                ),
            ),
        )

        assertEquals(listOf(NavigationManeuverType.ARRIVE), NavigationManeuverGenerator.generate(route).map { it.type })
    }

    @Test
    fun `guidance emits approach and now only once`() {
        val maneuver = NavigationManeuver(
            NavigationManeuverType.LEFT,
            TrackPoint(49.0, 8.0),
            distanceAlongRouteMeters = 200.0,
        )
        val route = GpxTrack(
            "Guidance",
            listOf(listOf(TrackPoint(49.0, 8.0), TrackPoint(49.01, 8.0))),
            navigationManeuvers = listOf(maneuver),
        )
        val tracker = NavigationGuidanceTracker(route)

        assertEquals(NavigationAnnouncementStage.APPROACH, tracker.update(100.0)?.announcement)
        assertNull(tracker.update(105.0)?.announcement)
        assertEquals(NavigationAnnouncementStage.NOW, tracker.update(185.0)?.announcement)
        assertNull(tracker.update(190.0)?.announcement)
    }

    @Test
    fun `suppressed guidance neither displays nor consumes announcement`() {
        val maneuver = NavigationManeuver(
            NavigationManeuverType.RIGHT,
            TrackPoint(49.0, 8.0),
            distanceAlongRouteMeters = 100.0,
        )
        val route = GpxTrack(
            "Off route",
            listOf(listOf(TrackPoint(49.0, 8.0), TrackPoint(49.01, 8.0))),
            navigationManeuvers = listOf(maneuver),
        )
        val tracker = NavigationGuidanceTracker(route)

        assertNull(tracker.update(20.0, suppress = true))
        assertEquals(NavigationAnnouncementStage.APPROACH, tracker.update(20.0)?.announcement)
    }
}
