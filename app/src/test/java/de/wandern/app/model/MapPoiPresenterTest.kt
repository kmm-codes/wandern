package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MapPoiPresenterTest {
    @Test
    fun usesSpecificTranslatedSubclass() {
        val poi = MapPoiPresenter.present(null, "information", "guidepost")

        assertEquals("Wegweiser", poi.title)
        assertEquals("Wegweiser", poi.category)
    }

    @Test
    fun keepsNameAndAddsCategory() {
        val poi = MapPoiPresenter.present("Merkurhütte", "lodging", "alpine_hut")

        assertEquals("Merkurhütte", poi.title)
        assertEquals("Berghütte", poi.category)
    }

    @Test
    fun humanizesUnknownOsmCategory() {
        val poi = MapPoiPresenter.present(null, "fire_station", null)

        assertEquals("Fire station", poi.title)
    }
}
