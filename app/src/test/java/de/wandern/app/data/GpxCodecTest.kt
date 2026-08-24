package de.wandern.app.data

import de.wandern.app.model.GpxTrack
import de.wandern.app.model.ActivityType
import de.wandern.app.model.ElevationSource
import de.wandern.app.model.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxCodecTest {
    @Test
    fun `round trip preserves e bike activity type`() {
        val original = GpxTrack(
            "Elektrische Runde",
            listOf(listOf(TrackPoint(48.0, 8.0))),
            activityType = ActivityType.E_BIKE,
        )

        val encoded = GpxCodec.encode(original)
        val parsed = GpxCodec.parse(encoded.byteInputStream())

        assertTrue(encoded.contains("<type>e-biking</type>"))
        assertEquals(ActivityType.E_BIKE, parsed.activityType)
    }

    @Test
    fun `recognizes common GPX cycling type`() {
        val gpx = """
            <gpx version="1.1"><trk><name>Runde</name><type>cycling</type><trkseg>
              <trkpt lat="48.0" lon="8.0"/>
            </trkseg></trk></gpx>
        """.trimIndent()

        assertEquals(ActivityType.CYCLING, GpxCodec.parse(gpx.byteInputStream()).activityType)
    }

    @Test
    fun `preserves the source of generated elevation data`() {
        val original = GpxTrack(
            "Höhenmodell",
            listOf(listOf(TrackPoint(48.0, 8.0, elevationMeters = 321.0))),
            elevationSource = ElevationSource.OPEN_METEO_COPERNICUS_GLO_90,
        )

        val parsed = GpxCodec.parse(GpxCodec.encode(original).byteInputStream())

        assertEquals(ElevationSource.OPEN_METEO_COPERNICUS_GLO_90, parsed.elevationSource)
    }

    @Test
    fun `parses namespaced track with elevation and time`() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
              <trk><name>Feierabendrunde</name><trkseg>
                <trkpt lat="48.1001" lon="11.5001"><ele>512.4</ele><time>2026-08-24T10:15:30Z</time></trkpt>
                <trkpt lat="48.1002" lon="11.5002"><ele>513.1</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = GpxCodec.parse(gpx.byteInputStream())

        assertEquals("Feierabendrunde", track.name)
        assertEquals(1, track.segments.size)
        assertEquals(2, track.points.size)
        assertEquals(512.4, track.points.first().elevationMeters!!, 0.001)
        assertEquals(1_787_566_530_000L, track.points.first().timeMillis)
    }

    @Test
    fun `parses route points when no track exists`() {
        val gpx = """
            <gpx version="1.1"><rte><rtept lat="47.0" lon="9.0"/><rtept lat="47.1" lon="9.1"/></rte></gpx>
        """.trimIndent()

        val track = GpxCodec.parse(gpx.byteInputStream(), "Route aus Datei")

        assertEquals("Route aus Datei", track.name)
        assertEquals(2, track.points.size)
    }

    @Test
    fun `round trip preserves segments coordinates and escaped name`() {
        val original = GpxTrack(
            "Wald & Wiese <3",
            listOf(
                listOf(TrackPoint(48.1, 11.5, 500.25, 1_700_000_000_000L)),
                listOf(TrackPoint(48.2, 11.6, 510.5, 1_700_000_100_000L)),
            ),
        )

        val encoded = GpxCodec.encode(original)
        val parsed = GpxCodec.parse(encoded.byteInputStream())

        assertTrue(encoded.contains("Wald &amp; Wiese &lt;3"))
        assertEquals(original.name, parsed.name)
        assertEquals(2, parsed.segments.size)
        assertEquals(original.points.map { it.latitude }, parsed.points.map { it.latitude })
    }

    @Test
    fun `round trip preserves interpolated gap markers`() {
        val original = GpxTrack(
            "GPS-Lücke",
            listOf(
                listOf(
                    TrackPoint(48.1, 11.5, timeMillis = 1_000L),
                    TrackPoint(48.2, 11.6, timeMillis = 6_000L, isInterpolated = true),
                    TrackPoint(48.3, 11.7, timeMillis = 11_000L),
                ),
            ),
        )

        val parsed = GpxCodec.parse(GpxCodec.encode(original).byteInputStream())

        assertTrue(parsed.points[1].isInterpolated)
        assertTrue(!parsed.points[0].isInterpolated)
        assertTrue(!parsed.points[2].isInterpolated)
    }

    @Test
    fun `rejects XML doctypes`() {
        val malicious = """
            <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <gpx version="1.1"><trk><name>&xxe;</name><trkseg><trkpt lat="1" lon="1"/></trkseg></trk></gpx>
        """.trimIndent()

        assertThrows(Exception::class.java) {
            GpxCodec.parse(ByteArrayInputStream(malicious.toByteArray()))
        }
    }
}
