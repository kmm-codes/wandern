package de.wandern.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsQualityWarningMonitorTest {
    @Test
    fun isolatedPoorFixDoesNotShowWarning() {
        val monitor = GpsQualityWarningMonitor(confirmationMillis = 10_000L, minimumPoorSamples = 3)

        assertFalse(monitor.update(isPoor = true, sampleMillis = 1_000L))
        assertFalse(monitor.update(isPoor = false, sampleMillis = 4_000L))
    }

    @Test
    fun sustainedPoorFixesEventuallyShowWarning() {
        val monitor = GpsQualityWarningMonitor(confirmationMillis = 10_000L, minimumPoorSamples = 3)

        assertFalse(monitor.update(isPoor = true, sampleMillis = 1_000L))
        assertFalse(monitor.update(isPoor = true, sampleMillis = 6_000L))
        assertTrue(monitor.update(isPoor = true, sampleMillis = 11_000L))
    }

    @Test
    fun repeatedRenderingOfSameFixDoesNotCountAsNewEvidence() {
        val monitor = GpsQualityWarningMonitor(confirmationMillis = 10_000L, minimumPoorSamples = 3)

        assertFalse(monitor.update(isPoor = true, sampleMillis = 1_000L))
        assertFalse(monitor.update(isPoor = true, sampleMillis = 1_000L))
        assertFalse(monitor.update(isPoor = true, sampleMillis = 12_000L))
    }

    @Test
    fun reliableFixClearsActiveWarningImmediately() {
        val monitor = GpsQualityWarningMonitor(confirmationMillis = 1_000L, minimumPoorSamples = 2)
        monitor.update(isPoor = true, sampleMillis = 1_000L)
        assertTrue(monitor.update(isPoor = true, sampleMillis = 2_000L))

        assertFalse(monitor.update(isPoor = false, sampleMillis = 3_000L))
    }
}
