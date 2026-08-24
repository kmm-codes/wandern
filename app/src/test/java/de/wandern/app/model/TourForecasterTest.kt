package de.wandern.app.model

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourForecasterTest {
    @Test
    fun `fitness profiles produce progressively shorter forecasts`() {
        val stats = TrackStats(distanceMeters = 10_000.0)

        val leisurely = TourForecaster.forecast(stats, fitnessLevel = HikingFitnessLevel.LEISURELY)!!
        val average = TourForecaster.forecast(stats, fitnessLevel = HikingFitnessLevel.AVERAGE)!!
        val fit = TourForecaster.forecast(stats, fitnessLevel = HikingFitnessLevel.FIT)!!
        val sporty = TourForecaster.forecast(stats, fitnessLevel = HikingFitnessLevel.SPORTY)!!

        assertTrue(leisurely.totalDurationMillis > average.totalDurationMillis)
        assertTrue(average.totalDurationMillis > fit.totalDurationMillis)
        assertTrue(fit.totalDurationMillis > sporty.totalDurationMillis)
        assertTrue(average.breakDurationMillis > 0L)
        assertTrue(average.totalDurationMillis > average.movingDurationMillis)
    }

    @Test
    fun `long routes have a lower forecast average speed because of fatigue`() {
        val short = TourForecaster.forecast(TrackStats(distanceMeters = 5_000.0))!!
        val long = TourForecaster.forecast(TrackStats(distanceMeters = 20_000.0))!!

        assertTrue(long.averageSpeedKilometersPerHour < short.averageSpeedKilometersPerHour)
    }

    @Test
    fun `elevation profile slows a steep route`() {
        val stats = TrackStats(distanceMeters = 2_000.0)
        val flat = listOf(ProfileSample(0.0, 100.0), ProfileSample(2_000.0, 100.0))
        val steep = listOf(ProfileSample(0.0, 100.0), ProfileSample(2_000.0, 500.0))

        val flatForecast = TourForecaster.forecast(stats, flat)!!
        val steepForecast = TourForecaster.forecast(stats, steep)!!

        assertTrue(steepForecast.movingDurationMillis > flatForecast.movingDurationMillis)
    }

    @Test
    fun `does not forecast an empty route`() {
        assertNull(TourForecaster.forecast(TrackStats()))
    }
}
