package de.wandern.app.model

import kotlin.math.ceil

data class ProfileSample(
    val distanceMeters: Double,
    val value: Double,
    val secondaryValue: Double? = null,
)

data class TourInsights(
    val stats: TrackStats,
    val elevationProfile: List<ProfileSample>,
    val speedProfile: List<ProfileSample>,
    val hasTimeData: Boolean,
)

object TourInsightsAnalyzer {
    private const val MAX_SPEED_INTERVAL_MILLIS = 120_000L
    private const val MAX_REASONABLE_SPEED_KMH = 50.0
    private const val MAX_PROFILE_SAMPLES = 320

    fun analyze(track: GpxTrack): TourInsights {
        val elevations = mutableListOf<ProfileSample>()
        val speeds = mutableListOf<ProfileSample>()
        var cumulativeDistance = 0.0
        var hasTimeData = false

        track.segments.forEach { segment ->
            segment.firstOrNull()?.elevationMeters?.let {
                elevations += ProfileSample(cumulativeDistance, it)
            }
            segment.zipWithNext().forEach { (previous, current) ->
                val distance = GeoMath.distanceMeters(previous, current)
                cumulativeDistance += distance
                current.elevationMeters?.let {
                    elevations += ProfileSample(cumulativeDistance, it)
                }

                val previousTime = previous.timeMillis
                val currentTime = current.timeMillis
                if (previousTime != null && currentTime != null) {
                    val interval = currentTime - previousTime
                    if (interval > 0 && interval <= MAX_SPEED_INTERVAL_MILLIS) {
                        hasTimeData = true
                        val speedKmh = distance / (interval / 1000.0) * 3.6
                        if (speedKmh in 0.0..MAX_REASONABLE_SPEED_KMH) {
                            speeds += ProfileSample(cumulativeDistance, speedKmh)
                        }
                    }
                }
            }
        }

        return TourInsights(
            stats = TrackAnalyzer.calculate(track),
            elevationProfile = downsample(addLocalSlopes(smoothValues(elevations))),
            speedProfile = downsample(smoothSpeeds(speeds)),
            hasTimeData = hasTimeData,
        )
    }

    private fun smoothSpeeds(samples: List<ProfileSample>): List<ProfileSample> {
        if (samples.size < 3) return samples
        val filtered = samples.mapIndexed { index, sample ->
            val neighborhood = samples
                .subList(maxOf(0, index - SMOOTHING_RADIUS), minOf(samples.size, index + SMOOTHING_RADIUS + 1))
                .map { it.value }
                .sorted()
            val median = neighborhood[neighborhood.size / 2]
            val allowedDeviation = maxOf(MIN_SPEED_OUTLIER_DELTA_KMH, median * SPEED_OUTLIER_FACTOR)
            sample.copy(value = if (kotlin.math.abs(sample.value - median) > allowedDeviation) median else sample.value)
        }
        return smoothValues(filtered)
    }

    private fun smoothValues(samples: List<ProfileSample>): List<ProfileSample> =
        samples.mapIndexed { index, sample ->
            val start = maxOf(0, index - SMOOTHING_RADIUS)
            val end = minOf(samples.lastIndex, index + SMOOTHING_RADIUS)
            var weightedSum = 0.0
            var totalWeight = 0.0
            for (neighborIndex in start..end) {
                val weight = (SMOOTHING_RADIUS + 1 - kotlin.math.abs(index - neighborIndex)).toDouble()
                weightedSum += samples[neighborIndex].value * weight
                totalWeight += weight
            }
            sample.copy(value = weightedSum / totalWeight)
        }

    private fun addLocalSlopes(samples: List<ProfileSample>): List<ProfileSample> =
        samples.mapIndexed { index, sample ->
            var before = index
            var after = index
            while (before > 0 && sample.distanceMeters - samples[before].distanceMeters < SLOPE_HALF_WINDOW_METERS) {
                before--
            }
            while (after < samples.lastIndex && samples[after].distanceMeters - sample.distanceMeters < SLOPE_HALF_WINDOW_METERS) {
                after++
            }
            val distance = samples[after].distanceMeters - samples[before].distanceMeters
            val slope = if (distance >= MIN_SLOPE_DISTANCE_METERS) {
                ((samples[after].value - samples[before].value) / distance * 100.0)
                    .coerceIn(-100.0, 100.0)
            } else {
                null
            }
            sample.copy(secondaryValue = slope)
        }

    private fun downsample(samples: List<ProfileSample>): List<ProfileSample> {
        if (samples.size <= MAX_PROFILE_SAMPLES) return samples
        val stride = ceil(samples.size / MAX_PROFILE_SAMPLES.toDouble()).toInt()
        return buildList {
            samples.filterIndexed { index, _ -> index % stride == 0 }.forEach(::add)
            if (lastOrNull() != samples.last()) add(samples.last())
        }
    }

    private const val SLOPE_HALF_WINDOW_METERS = 30.0
    private const val MIN_SLOPE_DISTANCE_METERS = 10.0
    private const val SMOOTHING_RADIUS = 2
    private const val MIN_SPEED_OUTLIER_DELTA_KMH = 2.5
    private const val SPEED_OUTLIER_FACTOR = 0.55
}
