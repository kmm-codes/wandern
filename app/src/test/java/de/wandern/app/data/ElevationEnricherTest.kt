package de.wandern.app.data

import de.wandern.app.model.ElevationSource
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ElevationEnricherTest {
    @Test
    fun `adds sampled and interpolated elevations to a track without elevation data`() {
        val track = GpxTrack(
            "Ohne Höhe",
            listOf(
                listOf(
                    TrackPoint(48.0, 8.0),
                    TrackPoint(48.0005, 8.0),
                    TrackPoint(48.0010, 8.0),
                ),
            ),
        )
        val requested = mutableListOf<TrackPoint>()
        val enricher = ElevationEnricher { points ->
            requested += points
            listOf(100.0, 200.0)
        }

        val enriched = enricher.enrichIfMissing(track)

        assertEquals(2, requested.size)
        val elevations = enriched.points.map { requireNotNull(it.elevationMeters) }
        assertEquals(100.0, elevations[0], 0.001)
        assertEquals(150.0, elevations[1], 0.001)
        assertEquals(200.0, elevations[2], 0.001)
        assertEquals(ElevationSource.OPEN_METEO_COPERNICUS_GLO_90, enriched.elevationSource)
    }

    @Test
    fun `keeps tracks that already contain elevation data unchanged`() {
        val track = GpxTrack(
            "Mit Höhe",
            listOf(listOf(TrackPoint(48.0, 8.0, elevationMeters = 250.0))),
        )
        val enricher = ElevationEnricher { error("Provider darf nicht aufgerufen werden") }

        assertSame(track, enricher.enrichIfMissing(track))
    }
}
