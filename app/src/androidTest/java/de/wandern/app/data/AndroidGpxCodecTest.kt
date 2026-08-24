package de.wandern.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGpxCodecTest {
    @Test
    fun parsesNamespacedGpxWithGarminExtensionsOnAndroid() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8" standalone="no" ?>
            <gpx version="1.1"
                xmlns="http://www.topografix.com/GPX/1/1"
                xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3"
                creator="Wandern Android Test">
              <trk>
                <name>Panoramaweg</name>
                <extensions><gpxx:TrackExtension><gpxx:DisplayColor>Red</gpxx:DisplayColor></gpxx:TrackExtension></extensions>
                <trkseg>
                  <trkpt lon="8.238657" lat="48.760327"><ele>165</ele></trkpt>
                  <trkpt lon="8.238898" lat="48.760390"><ele>167</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val track = GpxCodec.parse(gpx.byteInputStream())

        assertEquals("Panoramaweg", track.name)
        assertEquals(2, track.points.size)
        assertEquals(165.0, track.points.first().elevationMeters!!, 0.001)
        assertTrue(track.points.all { it.latitude in 48.0..49.0 })
    }
}
