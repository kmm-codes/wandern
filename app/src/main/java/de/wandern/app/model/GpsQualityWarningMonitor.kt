package de.wandern.app.model

/**
 * Debounces user-facing GPS quality warnings without weakening location filtering.
 * Poor fixes may still be rejected immediately; only the warning waits for sustained evidence.
 */
class GpsQualityWarningMonitor(
    private val confirmationMillis: Long = 15_000L,
    private val minimumPoorSamples: Int = 4,
) {
    private var poorSinceMillis: Long? = null
    private var poorSampleCount = 0
    private var lastSampleMillis: Long? = null
    private var warningActive = false

    fun update(isPoor: Boolean, sampleMillis: Long): Boolean {
        val previousSample = lastSampleMillis
        if (previousSample != null && sampleMillis <= previousSample) return warningActive
        lastSampleMillis = sampleMillis

        if (!isPoor) {
            poorSinceMillis = null
            poorSampleCount = 0
            warningActive = false
            return false
        }

        val startedAt = poorSinceMillis ?: sampleMillis.also { poorSinceMillis = it }
        poorSampleCount++
        if (
            poorSampleCount >= minimumPoorSamples &&
            sampleMillis - startedAt >= confirmationMillis
        ) {
            warningActive = true
        }
        return warningActive
    }

    fun reset() {
        poorSinceMillis = null
        poorSampleCount = 0
        lastSampleMillis = null
        warningActive = false
    }
}
