package de.wandern.app.model

import kotlin.math.abs
import kotlin.math.exp

data class TourForecast(
    val movingDurationMillis: Long,
    val breakDurationMillis: Long,
    val totalDurationMillis: Long,
    val paceSecondsPerKilometer: Double,
    val averageSpeedKilometersPerHour: Double,
)

enum class HikingFitnessLevel(
    val preferenceValue: String,
    val speedFactor: Double,
    val fatiguePerHourAfterThreshold: Double,
) {
    LEISURELY("leisurely", 0.75, 0.06),
    AVERAGE("average", 0.90, 0.045),
    FIT("fit", 1.05, 0.03),
    SPORTY("sporty", 1.20, 0.02),
    ;

    companion object {
        fun fromPreference(value: String?): HikingFitnessLevel =
            entries.firstOrNull { it.preferenceValue == value } ?: AVERAGE
    }
}

object TourForecaster {
    private const val TOBLER_MAX_SPEED_KMH = 6.0
    private const val TOBLER_SLOPE_FACTOR = 3.5
    private const val TOBLER_OPTIMAL_DESCENT = 0.05
    private const val DEFAULT_FLAT_SPEED_KMH = 5.0
    private const val FATIGUE_START_HOURS = 1.5
    private const val BREAK_MINUTES_PER_MOVING_HOUR = 8.0

    /** Terrain-aware estimate personalized by fitness, with fatigue after 90 minutes. */
    fun forecast(
        stats: TrackStats,
        elevationProfile: List<ProfileSample> = emptyList(),
        fitnessLevel: HikingFitnessLevel = HikingFitnessLevel.AVERAGE,
    ): TourForecast? {
        if (stats.distanceMeters <= 0.0) return null
        val terrainHours = terrainHours(stats.distanceMeters, elevationProfile, fitnessLevel)
        val fatigueHours = terrainHours * fitnessLevel.fatiguePerHourAfterThreshold *
            (terrainHours - FATIGUE_START_HOURS).coerceAtLeast(0.0)
        val movingHours = terrainHours + fatigueHours
        if (movingHours <= 0.0) return null
        val breakHours = movingHours * BREAK_MINUTES_PER_MOVING_HOUR / 60.0
        val distanceKilometers = stats.distanceMeters / 1_000.0
        return TourForecast(
            movingDurationMillis = hoursToMillis(movingHours),
            breakDurationMillis = hoursToMillis(breakHours),
            totalDurationMillis = hoursToMillis(movingHours + breakHours),
            paceSecondsPerKilometer = movingHours * 3_600.0 / distanceKilometers,
            averageSpeedKilometersPerHour = distanceKilometers / movingHours,
        )
    }

    private fun terrainHours(
        totalDistanceMeters: Double,
        elevationProfile: List<ProfileSample>,
        fitnessLevel: HikingFitnessLevel,
    ): Double {
        var coveredDistance = 0.0
        var hours = 0.0
        elevationProfile.zipWithNext().forEach { (start, end) ->
            val distance = end.distanceMeters - start.distanceMeters
            if (distance <= 0.0) return@forEach
            val localSlopes = listOfNotNull(start.secondaryValue, end.secondaryValue)
            val slope = (
                localSlopes.takeIf { it.isNotEmpty() }?.average()?.div(100.0)
                    ?: ((end.value - start.value) / distance)
                ).coerceIn(-0.6, 0.6)
            val speed = TOBLER_MAX_SPEED_KMH * exp(
                -TOBLER_SLOPE_FACTOR * abs(slope + TOBLER_OPTIMAL_DESCENT),
            ) * fitnessLevel.speedFactor
            hours += distance / 1_000.0 / speed.coerceAtLeast(0.5)
            coveredDistance += distance
        }
        val uncoveredDistance = (totalDistanceMeters - coveredDistance).coerceAtLeast(0.0)
        hours += uncoveredDistance / 1_000.0 /
            (DEFAULT_FLAT_SPEED_KMH * fitnessLevel.speedFactor)
        return hours
    }

    private fun hoursToMillis(hours: Double) = (hours * 3_600_000.0).toLong()
}
