package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteVariantPolicyTest {
    @Test
    fun reversingAClosedRouteKeepsTheCompleteLoop() {
        val start = TrackPoint(49.0, 8.0)
        val firstLeg = TrackPoint(49.01, 8.0)
        val secondLeg = TrackPoint(49.01, 8.01)
        val route = GpxTrack(
            "Rundweg",
            listOf(listOf(start, firstLeg), listOf(firstLeg, secondLeg, start)),
        )

        val reversed = RouteVariantPolicy.reversed(route)

        assertEquals(listOf(start, secondLeg, firstLeg, firstLeg, start), reversed.points)
        assertTrue(RouteVariantPolicy.isClosed(reversed))
    }

    @Test
    fun `out and back mirrors selected outbound alternative`() {
        val start = TrackPoint(49.0, 8.0)
        val middle = TrackPoint(49.001, 8.001)
        val destination = TrackPoint(49.002, 8.002)

        val sourceAttribute = RouteAttributeSegment(100.0, RouteWayType.TRACK, RouteSurface.GRAVEL)
        val result = RouteVariantPolicy.asOutAndBack(
            GpxTrack(
                "Alternative",
                listOf(listOf(start, middle, destination)),
                routeAttributes = listOf(sourceAttribute),
            ),
        )

        assertEquals(listOf(start, middle, destination, middle, start), result.points)
        assertEquals(listOf(sourceAttribute, sourceAttribute), result.routeAttributes)
    }

    @Test
    fun `rejects a return along the same path as a round trip`() {
        val start = TrackPoint(49.0, 8.0)
        val middle = TrackPoint(49.001, 8.001)
        val destination = TrackPoint(49.002, 8.002)
        val mirrored = GpxTrack(
            "Mirrored",
            listOf(listOf(start, middle, destination, middle, start, start)),
        )

        assertFalse(RouteVariantPolicy.isGenuineLoop(mirrored, destination))
    }

    @Test
    fun `accepts a meaningfully different return path`() {
        val start = TrackPoint(49.0, 8.0)
        val destination = TrackPoint(49.002, 8.0)
        val loop = GpxTrack(
            "Loop",
            listOf(
                listOf(
                    start,
                    TrackPoint(49.001, 8.0),
                    destination,
                    TrackPoint(49.002, 8.003),
                    TrackPoint(49.001, 8.003),
                    TrackPoint(49.0, 8.003),
                    start,
                ),
            ),
        )

        assertTrue(RouteVariantPolicy.isGenuineLoop(loop, destination))
    }

    @Test
    fun `combines separate outbound and inbound alternatives into a loop`() {
        val start = TrackPoint(49.0, 8.0)
        val destination = TrackPoint(49.003, 8.0)
        val outbound = GpxTrack(
            "Hinweg 1",
            listOf(
                listOf(
                    start,
                    TrackPoint(49.001, 7.998),
                    TrackPoint(49.002, 7.998),
                    destination,
                ),
            ),
        )
        val inbound = GpxTrack(
            "Rückweg 3",
            listOf(
                listOf(
                    destination,
                    TrackPoint(49.002, 8.002),
                    TrackPoint(49.001, 8.002),
                    start,
                ),
            ),
        )

        val result = RouteVariantPolicy.combineAsLoop(outbound, inbound)

        assertEquals(
            listOf(
                start,
                TrackPoint(49.001, 7.998),
                TrackPoint(49.002, 7.998),
                destination,
                TrackPoint(49.002, 8.002),
                TrackPoint(49.001, 8.002),
                start,
            ),
            result.points,
        )
        assertTrue(RouteVariantPolicy.isGenuineLoop(result, destination))
    }
}
