package de.wandern.app.debug

import de.wandern.app.model.TourInsightsAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTourFactoryTest {
    @Test
    fun `creates the same realistic recorded tour every time`() {
        val first = DemoTourFactory.create()
        val second = DemoTourFactory.create()

        assertEquals(first, second)
        assertEquals(DemoTourFactory.TOUR_NAME, first.name)
        assertEquals(245, first.points.size)
        assertTrue(first.points.all { it.timeMillis != null && it.elevationMeters != null })

        val insights = TourInsightsAnalyzer.analyze(first)
        assertTrue(insights.hasTimeData)
        assertTrue(insights.stats.distanceMeters > 10_000.0)
        assertTrue(insights.stats.ascentMeters > 300.0)
        assertTrue(insights.stats.movingDurationMillis < insights.stats.durationMillis)
        assertTrue(insights.speedProfile.size > 100)
    }
}
