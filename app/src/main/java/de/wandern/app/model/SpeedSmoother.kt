package de.wandern.app.model

/** Keeps the live speed readable without hiding prolonged stops. */
class SpeedSmoother(
    private val windowMillis: Long = 12_000L,
    private val reliableAccuracyMeters: Float = GpsQuality.RELIABLE_ACCURACY_METERS,
) {
    private data class Sample(val timeMillis: Long, val metersPerSecond: Double)

    private val samples = ArrayDeque<Sample>()
    private var previousReliablePoint: TrackPoint? = null

    fun update(point: TrackPoint): Double? {
        val timeMillis = point.timeMillis ?: return null
        trimBefore(timeMillis - windowMillis)
        if ((point.accuracyMeters ?: 0f) > reliableAccuracyMeters) return null

        val previous = previousReliablePoint
        val speed = point.speedMetersPerSecond?.toDouble() ?: derivedSpeed(previous, point)
        if (previous == null || timeMillis >= (previous.timeMillis ?: Long.MIN_VALUE)) {
            previousReliablePoint = point
        }
        if (speed == null || !speed.isFinite() || speed < 0.0) return null

        if (samples.lastOrNull()?.timeMillis != timeMillis) {
            samples.addLast(Sample(timeMillis, speed))
        }
        return samples.map { it.metersPerSecond }.average().takeIf { samples.isNotEmpty() }
    }

    fun reset() {
        samples.clear()
        previousReliablePoint = null
    }

    private fun derivedSpeed(previous: TrackPoint?, current: TrackPoint): Double? {
        previous ?: return null
        val previousTime = previous.timeMillis ?: return null
        val currentTime = current.timeMillis ?: return null
        val elapsedMillis = currentTime - previousTime
        if (elapsedMillis !in MIN_DERIVATION_INTERVAL_MILLIS..MAX_DERIVATION_INTERVAL_MILLIS) return null
        return GeoMath.distanceMeters(previous, current) / (elapsedMillis / 1000.0)
    }

    private fun trimBefore(cutoffMillis: Long) {
        while (samples.firstOrNull()?.timeMillis?.let { it < cutoffMillis } == true) {
            samples.removeFirst()
        }
    }

    companion object {
        private const val MIN_DERIVATION_INTERVAL_MILLIS = 500L
        private const val MAX_DERIVATION_INTERVAL_MILLIS = 20_000L
    }
}
