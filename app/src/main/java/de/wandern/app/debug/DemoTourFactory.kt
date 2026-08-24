package de.wandern.app.debug

import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

object DemoTourFactory {
    const val TOUR_NAME = "Demo-Aufzeichnung Feldberg"
    private const val WALKING_POINT_COUNT = 240
    private const val START_TIME = "2026-08-24T08:00:00Z"

    fun create(): GpxTrack {
        val coordinates = (0..WALKING_POINT_COUNT).map { index ->
            val angle = 2.0 * PI * index / WALKING_POINT_COUNT
            val latitude = 47.8720 + 0.0170 * sin(angle) + 0.0022 * sin(angle * 3.0)
            val longitude = 8.0030 + 0.0240 * cos(angle) + 0.0030 * cos(angle * 2.0)
            val elevation = 1_150.0 +
                155.0 * sin(angle - 0.7) +
                70.0 * sin(angle * 3.0 + 0.4) +
                22.0 * cos(angle * 7.0)
            Coordinate(latitude, longitude, elevation)
        }

        var timeMillis = Instant.parse(START_TIME).toEpochMilli()
        val points = mutableListOf<TrackPoint>()
        coordinates.forEachIndexed { index, coordinate ->
            if (index > 0) {
                val previous = coordinates[index - 1]
                val distance = GeoMath.distanceMeters(previous.toPoint(), coordinate.toPoint())
                val angle = 2.0 * PI * index / WALKING_POINT_COUNT
                val targetSpeedKmh = (4.7 + 1.15 * sin(angle * 4.0) - 0.55 * cos(angle * 7.0))
                    .coerceIn(2.7, 6.4)
                timeMillis += (distance / (targetSpeedKmh / 3.6) * 1_000.0).roundToLong()
            }
            points += coordinate.toPoint(timeMillis)

            // Four stationary minutes make total and moving time visibly different.
            if (index == WALKING_POINT_COUNT / 2) {
                repeat(4) {
                    timeMillis += 60_000L
                    points += coordinate.toPoint(timeMillis, speedMetersPerSecond = 0f)
                }
            }
        }
        return GpxTrack(TOUR_NAME, listOf(points))
    }

    private data class Coordinate(
        val latitude: Double,
        val longitude: Double,
        val elevation: Double,
    ) {
        fun toPoint(timeMillis: Long? = null, speedMetersPerSecond: Float? = null) = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            elevationMeters = elevation,
            timeMillis = timeMillis,
            accuracyMeters = 6f,
            speedMetersPerSecond = speedMetersPerSecond,
        )
    }
}
