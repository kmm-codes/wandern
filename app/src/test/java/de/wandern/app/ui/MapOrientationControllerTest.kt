package de.wandern.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapOrientationControllerTest {
    @Test
    fun straightensAManuallyRotatedMapBeforeSwitchingMode() {
        val action = MapOrientationController.nextAction(
            mode = MapOrientationMode.NORTH_UP,
            bearingDegrees = 47.0,
            following = true,
        )
        assertEquals(MapOrientationAction.ALIGN_NORTH, action)
        assertEquals(MapOrientationMode.NORTH_UP, MapOrientationController.modeAfter(action))
    }

    @Test
    fun startsHeadingUpFromAnAlreadyNorthAlignedMap() {
        val action = MapOrientationController.nextAction(
            mode = MapOrientationMode.NORTH_UP,
            bearingDegrees = 0.0,
            following = false,
        )
        assertEquals(MapOrientationAction.START_HEADING_UP, action)
        assertEquals(MapOrientationMode.HEADING_UP, MapOrientationController.modeAfter(action))
    }

    @Test
    fun treatsANearlyNorthBearingAsNorth() {
        assertEquals(
            MapOrientationAction.START_HEADING_UP,
            MapOrientationController.nextAction(
                mode = MapOrientationMode.NORTH_UP,
                bearingDegrees = 359.5,
                following = false,
            ),
        )
    }

    @Test
    fun stopsSteeringHeadingUpEvenWhileTheMapIsTurned() {
        val action = MapOrientationController.nextAction(
            mode = MapOrientationMode.HEADING_UP,
            bearingDegrees = 128.0,
            following = true,
        )
        assertEquals(MapOrientationAction.STOP_HEADING_UP, action)
        assertEquals(MapOrientationMode.NORTH_UP, MapOrientationController.modeAfter(action))
    }

    @Test
    fun alignsNorthWhenHeadingUpIsPausedByAPanGesture() {
        assertEquals(
            MapOrientationAction.ALIGN_NORTH,
            MapOrientationController.nextAction(
                mode = MapOrientationMode.HEADING_UP,
                bearingDegrees = 128.0,
                following = false,
            ),
        )
    }

    @Test
    fun leavesHeadingUpWhenItIsPausedOnAnAlreadyNorthAlignedMap() {
        assertEquals(
            MapOrientationAction.STOP_HEADING_UP,
            MapOrientationController.nextAction(
                mode = MapOrientationMode.HEADING_UP,
                bearingDegrees = 0.4,
                following = false,
            ),
        )
    }
}
