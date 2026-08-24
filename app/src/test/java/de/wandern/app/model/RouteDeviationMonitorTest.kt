package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDeviationMonitorTest {
    private val monitor = RouteDeviationMonitor(reminderIntervalMillis = 60_000L)

    @Test
    fun confirmsLeavingRouteOnlyAfterThreeReliableFixes() {
        assertFalse(monitor.update(60.0, 5f, 0L).confirmedOffRoute)
        assertFalse(monitor.update(65.0, 5f, 3_000L).confirmedOffRoute)

        val update = monitor.update(70.0, 5f, 6_000L)

        assertTrue(update.confirmedOffRoute)
        assertEquals(RouteDeviationEvent.LEFT_ROUTE, update.event)
    }

    @Test
    fun inaccurateFixesNeitherTriggerNorClearWarning() {
        repeat(3) { monitor.update(70.0, 5f, it * 3_000L) }

        val update = monitor.update(5.0, 80f, 12_000L)

        assertTrue(update.confirmedOffRoute)
        assertEquals(RouteDeviationEvent.NONE, update.event)
    }

    @Test
    fun confirmsReturnWithSeparateHysteresis() {
        repeat(3) { monitor.update(70.0, 5f, it * 3_000L) }
        monitor.update(30.0, 5f, 10_000L)
        monitor.update(35.0, 5f, 13_000L)

        val update = monitor.update(20.0, 5f, 16_000L)

        assertFalse(update.confirmedOffRoute)
        assertEquals(RouteDeviationEvent.RETURNED_TO_ROUTE, update.event)
    }

    @Test
    fun remindersAreRateLimited() {
        repeat(3) { monitor.update(70.0, 5f, it * 3_000L) }
        assertEquals(RouteDeviationEvent.NONE, monitor.update(80.0, 5f, 30_000L).event)

        val reminder = monitor.update(80.0, 5f, 66_000L)

        assertEquals(RouteDeviationEvent.OFF_ROUTE_REMINDER, reminder.event)
        assertEquals(RouteDeviationEvent.NONE, monitor.update(80.0, 5f, 70_000L).event)
    }
}
