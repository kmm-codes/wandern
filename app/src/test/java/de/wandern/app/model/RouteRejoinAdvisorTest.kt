package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRejoinAdvisorTest {
    @Test
    fun `returns direction and distance to projected route point`() {
        val route = GpxTrack(
            name = "Nordweg",
            segments = listOf(
                listOf(
                    TrackPoint(49.0, 8.0),
                    TrackPoint(49.01, 8.0),
                ),
            ),
        )

        val guidance = requireNotNull(
            RouteRejoinAdvisor(route).advise(TrackPoint(49.005, 8.001)),
        )

        assertTrue(guidance.distanceMeters in 70.0..80.0)
        assertTrue(guidance.bearingDegrees in 260.0..280.0)
        assertEquals(49.005, guidance.target.latitude, 0.00001)
    }

    @Test
    fun `progress anchor avoids geometrically close old branch`() {
        val route = GpxTrack(
            name = "Schleife",
            segments = listOf(
                listOf(
                    TrackPoint(49.0000, 8.0000),
                    TrackPoint(49.0000, 8.0100),
                    TrackPoint(49.0010, 8.0100),
                    TrackPoint(49.0010, 8.0000),
                ),
            ),
        )
        val advisor = RouteRejoinAdvisor(route)
        val position = TrackPoint(49.0001, 8.0050)

        val withoutProgress = requireNotNull(advisor.advise(position))
        val onReturnLeg = requireNotNull(advisor.advise(position, progressAnchorMeters = 1_200.0))

        assertTrue(withoutProgress.target.latitude < 49.0005)
        assertTrue(onReturnLeg.target.latitude > 49.0005)
    }
}
