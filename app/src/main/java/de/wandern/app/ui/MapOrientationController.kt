package de.wandern.app.ui

import kotlin.math.abs

/** North-up keeps north on top, heading-up turns the map into the walking direction. */
enum class MapOrientationMode {
    NORTH_UP,
    HEADING_UP,
}

/** What the next tap on the compass button does. */
enum class MapOrientationAction {
    /** Straightens a map the hiker rotated by hand before any mode change happens. */
    ALIGN_NORTH,
    START_HEADING_UP,
    STOP_HEADING_UP,
}

/**
 * Decides how the compass button reacts. Free of Android types so the tap rules stay unit tested.
 */
object MapOrientationController {
    /** Bearings below this count as north, so rounding noise does not swallow a mode change. */
    const val NORTH_TOLERANCE_DEGREES = 1.0

    fun nextAction(
        mode: MapOrientationMode,
        bearingDegrees: Double,
        following: Boolean,
    ): MapOrientationAction {
        val steering = mode == MapOrientationMode.HEADING_UP && following
        if (!steering && abs(signedBearingDegrees(bearingDegrees)) > NORTH_TOLERANCE_DEGREES) {
            return MapOrientationAction.ALIGN_NORTH
        }
        return if (mode == MapOrientationMode.NORTH_UP) {
            MapOrientationAction.START_HEADING_UP
        } else {
            MapOrientationAction.STOP_HEADING_UP
        }
    }

    fun modeAfter(action: MapOrientationAction): MapOrientationMode = when (action) {
        MapOrientationAction.START_HEADING_UP -> MapOrientationMode.HEADING_UP
        MapOrientationAction.ALIGN_NORTH, MapOrientationAction.STOP_HEADING_UP ->
            MapOrientationMode.NORTH_UP
    }

    private fun signedBearingDegrees(degrees: Double): Double =
        ((degrees % 360.0) + 540.0) % 360.0 - 180.0
}
