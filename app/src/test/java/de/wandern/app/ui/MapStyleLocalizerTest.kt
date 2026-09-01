package de.wandern.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleLocalizerTest {
    @Test
    fun recognizesMapNameExpressionsWithoutTouchingRoadShields() {
        assertTrue(
            MapStyleLocalizer.shouldLocalizeTextField(
                "[coalesce, [get, name_en], [get, name]]",
            ),
        )
        assertTrue(MapStyleLocalizer.shouldLocalizeTextField("[get, name:nonlatin]"))
        assertFalse(MapStyleLocalizer.shouldLocalizeTextField("[to-string, [get, ref]]"))
        assertFalse(MapStyleLocalizer.shouldLocalizeTextField("[get, housenumber]"))
    }
}
