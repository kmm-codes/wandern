package de.wandern.app.data

import de.wandern.app.model.ActivityType
import de.wandern.app.model.TrackPoint
import de.wandern.app.model.RouteSurface
import de.wandern.app.model.RouteWayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class OnlineRoutingClientTest {
    @Test
    fun `request contains ordered waypoints and matching profile`() {
        val url = OnlineRoutingClient.buildRequestUrl(
            endpoint = "https://example.test/brouter",
            waypoints = listOf(
                TrackPoint(49.0, 8.0),
                TrackPoint(49.1, 8.1),
                TrackPoint(49.2, 8.2),
            ),
            activityType = ActivityType.HIKING,
            routeName = "Test Tour",
            alternativeIndex = 2,
        )
        val decoded = URLDecoder.decode(url, StandardCharsets.UTF_8.name())

        assertTrue(decoded.contains("lonlats=8.0,49.0,m|8.1,49.1,m|8.2,49.2,m"))
        assertTrue(decoded.contains("profile=hiking-beta"))
        assertTrue(decoded.contains("trackname=Test Tour"))
        assertTrue(decoded.contains("alternativeidx=2"))
    }

    @Test
    fun `parses route coordinates and elevation`() {
        val track = OnlineRoutingClient.parseGeoJson(
            geoJson = """
                {
                  "type": "FeatureCollection",
                  "features": [{
                    "type": "Feature",
                    "geometry": {
                      "type": "LineString",
                      "coordinates": [[8.0, 49.0, 120], [8.1, 49.1, 135]]
                    }
                  }]
                }
            """.trimIndent(),
            routeName = "Test",
            activityType = ActivityType.CYCLING,
        )

        assertEquals("Test", track.name)
        assertEquals(ActivityType.CYCLING, track.activityType)
        assertEquals(2, track.points.size)
        assertEquals(135.0, track.points.last().elevationMeters!!, 0.0)
    }

    @Test
    fun `parses and merges BRouter way attributes`() {
        val track = OnlineRoutingClient.parseGeoJson(
            geoJson = """
                {
                  "type": "FeatureCollection",
                  "features": [{
                    "type": "Feature",
                    "properties": {
                      "messages": [
                        ["Longitude", "Latitude", "Distance", "WayTags"],
                        ["8.0", "49.0", "120", "highway=path surface=ground sac_scale=mountain_hiking"],
                        ["8.1", "49.1", "80", "highway=path surface=ground sac_scale=mountain_hiking"],
                        ["8.2", "49.2", "50", "highway=service surface=asphalt"]
                      ]
                    },
                    "geometry": {
                      "type": "LineString",
                      "coordinates": [[8.0, 49.0], [8.2, 49.2]]
                    }
                  }]
                }
            """.trimIndent(),
            routeName = "Test",
            activityType = ActivityType.HIKING,
        )

        assertEquals(2, track.routeAttributes.size)
        assertEquals(200.0, track.routeAttributes.first().distanceMeters, 0.0)
        assertEquals(RouteWayType.MOUNTAIN_TRAIL, track.routeAttributes.first().wayType)
        assertEquals(RouteSurface.NATURAL, track.routeAttributes.first().surface)
        assertEquals(RouteWayType.MINOR_ROAD, track.routeAttributes.last().wayType)
        assertEquals(RouteSurface.ASPHALT, track.routeAttributes.last().surface)
    }

    @Test
    fun `request can block sampled points from a selected route`() {
        val url = OnlineRoutingClient.buildRequestUrl(
            endpoint = "https://example.test/brouter",
            waypoints = listOf(TrackPoint(49.0, 8.0), TrackPoint(49.1, 8.1)),
            activityType = ActivityType.HIKING,
            routeName = "Rückweg",
            noGoPoints = listOf(
                RoutingNoGoPoint(TrackPoint(49.05, 8.05), radiusMeters = 45),
            ),
        )

        val decoded = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        assertTrue(decoded.contains("nogos=8.05,49.05,45"))
    }
}
