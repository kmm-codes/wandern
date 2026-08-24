package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStartAssessorTest {
    private val route = GpxTrack(
        name = "Gerade",
        segments = listOf(
            listOf(
                TrackPoint(48.0, 8.0),
                TrackPoint(48.0, 8.01),
                TrackPoint(48.0, 8.02),
            ),
        ),
    )

    @Test
    fun recommendsOfficialStartNearFirstPoint() {
        val assessment = RouteStartAssessor.assess(route, TrackPoint(48.0002, 8.0))!!

        assertEquals(RouteStartSituation.AT_START, assessment.situation)
        assertEquals(RouteEntryMode.OFFICIAL_START, assessment.recommendedEntryMode)
    }

    @Test
    fun recommendsNearestPointWhenAlreadyOnRoute() {
        val assessment = RouteStartAssessor.assess(route, TrackPoint(48.0001, 8.01))!!

        assertEquals(RouteStartSituation.ON_ROUTE, assessment.situation)
        assertEquals(RouteEntryMode.NEAREST_POINT, assessment.recommendedEntryMode)
        assertTrue(assessment.distanceAlongRouteMeters!! > 500.0)
    }

    @Test
    fun distinguishesNearRouteFromFarAway() {
        val near = RouteStartAssessor.assess(route, TrackPoint(48.0015, 8.01))!!
        val far = RouteStartAssessor.assess(route, TrackPoint(48.01, 8.01))!!

        assertEquals(RouteStartSituation.NEAR_ROUTE, near.situation)
        assertEquals(RouteEntryMode.NEAREST_POINT, near.recommendedEntryMode)
        assertEquals(RouteStartSituation.AWAY_FROM_ROUTE, far.situation)
        assertEquals(RouteEntryMode.OFFICIAL_START, far.recommendedEntryMode)
    }
}
