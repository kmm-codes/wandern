package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressCalculatorTest {
    @Test
    fun `projects current position onto route and calculates remaining values`() {
        val route = straightTrack(
            elevations = listOf(100.0, 100.0, 120.0, 160.0, 200.0, 180.0, 140.0, 120.0, 120.0),
        )
        val calculator = RouteProgressCalculator(route)

        val progress = calculator.calculate(point(index = 3.0))

        assertNotNull(progress)
        progress!!
        assertEquals(3.0 / 8.0, progress.fraction, 0.01)
        assertEquals(progress.totalDistanceMeters * 5.0 / 8.0, progress.remainingDistanceMeters, 2.0)
        assertTrue(progress.remainingAscentMeters > 0.0)
        assertTrue(progress.remainingDescentMeters > 0.0)
        assertEquals(0.0, progress.remainingElevationProfile.first().distanceMeters, 0.001)
    }

    @Test
    fun `uses previous progress to resolve a crossing at a circular route endpoint`() {
        val route = GpxTrack(
            name = "Runde",
            segments = listOf(
                listOf(
                    TrackPoint(48.0, 8.0),
                    TrackPoint(48.0, 8.01),
                    TrackPoint(48.01, 8.01),
                    TrackPoint(48.01, 8.0),
                    TrackPoint(48.0, 8.0),
                ),
            ),
        )
        val calculator = RouteProgressCalculator(route)
        val total = calculator.initial()!!.totalDistanceMeters

        val atStart = calculator.calculate(TrackPoint(48.0, 8.0))!!
        val nearFinish = calculator.calculate(TrackPoint(48.0, 8.0), total - 20.0)!!

        assertEquals(0.0, atStart.distanceAlongRouteMeters, 0.5)
        assertEquals(total, nearFinish.distanceAlongRouteMeters, 0.5)
    }

    @Test
    fun `tracker ignores off route observations`() {
        val tracker = RouteProgressTracker(straightTrack(List(20) { 100.0 }))

        val progress = tracker.update(TrackPoint(latitude = 49.0, longitude = 9.0, accuracyMeters = 8f))!!

        assertEquals(0.0, progress.distanceAlongRouteMeters, 0.1)
    }

    @Test
    fun `official start mode keeps progress at zero until start is reached`() {
        val route = straightTrack(List(20) { 100.0 })
        val tracker = RouteProgressTracker(route, RouteEntryMode.OFFICIAL_START)

        val beforeStart = tracker.update(point(index = 10.0, timeMillis = 1_000L))!!
        val atStart = tracker.update(point(index = 0.1, timeMillis = 2_000L))!!
        val underway = tracker.update(point(index = 1.0, timeMillis = 32_000L))!!

        assertEquals(0.0, beforeStart.distanceAlongRouteMeters, 0.1)
        assertTrue(atStart.distanceAlongRouteMeters < 50.0)
        assertTrue(underway.distanceAlongRouteMeters > 100.0)
    }

    @Test
    fun `nearest point mode starts progress at current route position`() {
        val tracker = RouteProgressTracker(
            straightTrack(List(20) { 100.0 }),
            RouteEntryMode.NEAREST_POINT,
        )

        val progress = tracker.update(point(index = 10.0))!!

        assertTrue(progress.distanceAlongRouteMeters > 1_000.0)
    }

    @Test
    fun `reversing a track reverses segment and point direction without changing original`() {
        val first = listOf(point(0.0), point(1.0))
        val second = listOf(point(2.0), point(3.0))
        val original = GpxTrack("Mehrteilig", listOf(first, second))

        val reversed = original.reversed()

        assertEquals(point(3.0), reversed.points.first())
        assertEquals(point(0.0), reversed.points.last())
        assertEquals(point(0.0), original.points.first())
        assertEquals("Mehrteilig", reversed.name)
    }

    @Test
    fun `tracker requires confirmation before accepting an implausible jump`() {
        val tracker = RouteProgressTracker(straightTrack(List(30) { 100.0 }))
        val start = point(index = 0.0, timeMillis = 1_000L)
        val jump = point(index = 20.0, timeMillis = 2_000L)
        val confirmation = point(index = 20.05, timeMillis = 3_000L)

        val acceptedStart = tracker.update(start)!!
        val rejectedJump = tracker.update(jump)!!
        val acceptedJump = tracker.update(confirmation)!!

        assertEquals(acceptedStart.distanceAlongRouteMeters, rejectedJump.distanceAlongRouteMeters, 0.1)
        assertTrue(acceptedJump.distanceAlongRouteMeters > 2_000.0)
    }

    private fun straightTrack(elevations: List<Double>) = GpxTrack(
        name = "Gerade",
        segments = listOf(elevations.mapIndexed { index, elevation -> point(index.toDouble(), elevationMeters = elevation) }),
    )

    private fun point(
        index: Double,
        elevationMeters: Double? = 100.0,
        timeMillis: Long? = null,
    ) = TrackPoint(
        latitude = 48.0,
        longitude = 8.0 + index * 0.0015,
        elevationMeters = elevationMeters,
        timeMillis = timeMillis,
        accuracyMeters = 5f,
    )
}
