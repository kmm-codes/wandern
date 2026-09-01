package de.wandern.app.data

import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RouteAttributeClassifier
import de.wandern.app.model.RouteAttributeSegment
import de.wandern.app.model.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RoutingNoGoPoint(
    val point: TrackPoint,
    val radiusMeters: Int,
)

class OnlineRoutingClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URI(url).toURL().openConnection() as HttpURLConnection
    },
    private val language: AppLanguage = AppLanguage.forSystem(),
) {
    fun calculate(
        waypoints: List<TrackPoint>,
        activityType: ActivityType,
        routeName: String = "Planned tour",
        alternativeIndex: Int = 0,
        noGoPoints: List<RoutingNoGoPoint> = emptyList(),
    ): GpxTrack {
        require(waypoints.size >= 2) {
            localized(language, "A route requires at least a start and destination.", "Für eine Route werden mindestens Start und Ziel benötigt.")
        }
        require(alternativeIndex >= 0) {
            localized(language, "The alternative index cannot be negative.", "Der Alternativindex darf nicht negativ sein.")
        }
        val url = buildRequestUrl(endpoint, waypoints, activityType, routeName, alternativeIndex, noGoPoints)
        val connection = connectionFactory(url).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/geo+json, application/vnd.geo+json, application/json")
            setRequestProperty("User-Agent", "Wandern-Android/0.1")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException(
                    localized(
                        language,
                        "Routing service returned HTTP $status${body.summarySuffix()}",
                        "Routingdienst antwortet mit HTTP $status${body.summarySuffix()}",
                    ),
                )
            }
            return parseGeoJson(body, routeName, activityType, language)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://brouter.de/brouter"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 60_000

        internal fun buildRequestUrl(
            endpoint: String,
            waypoints: List<TrackPoint>,
            activityType: ActivityType,
            routeName: String,
            alternativeIndex: Int = 0,
            noGoPoints: List<RoutingNoGoPoint> = emptyList(),
        ): String {
            val lonLats = waypoints.joinToString("|") { point ->
                "${point.longitude},${point.latitude},m"
            }
            val params = linkedMapOf(
                "lonlats" to lonLats,
                "profile" to profileFor(activityType),
                "alternativeidx" to alternativeIndex.toString(),
                "format" to "geojson",
                "trackname" to routeName,
            )
            if (noGoPoints.isNotEmpty()) {
                params["nogos"] = noGoPoints.joinToString("|") { noGo ->
                    "${noGo.point.longitude},${noGo.point.latitude},${noGo.radiusMeters.coerceAtLeast(1)}"
                }
            }
            return endpoint.trimEnd('/') + "?" + params.entries.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
        }

        internal fun parseGeoJson(
            geoJson: String,
            routeName: String,
            activityType: ActivityType,
            language: AppLanguage = AppLanguage.ENGLISH,
        ): GpxTrack {
            val root = JSONObject(geoJson)
            val features = root.optJSONArray("features")
                ?: throw IOException(
                    localized(
                        language,
                        "The routing service did not return a route.",
                        "Der Routingdienst hat keine Route geliefert.",
                    ),
                )
            val segments = buildList {
                for (featureIndex in 0 until features.length()) {
                    val geometry = features.optJSONObject(featureIndex)?.optJSONObject("geometry") ?: continue
                    when (geometry.optString("type")) {
                        "LineString" -> add(parseCoordinates(geometry.optJSONArray("coordinates")))
                        "MultiLineString" -> {
                            val lines = geometry.optJSONArray("coordinates") ?: continue
                            for (lineIndex in 0 until lines.length()) {
                                add(parseCoordinates(lines.optJSONArray(lineIndex)))
                            }
                        }
                    }
                }
            }.filter { it.size >= 2 }
            if (segments.isEmpty()) {
                throw IOException(
                    localized(
                        language,
                        "The routing service did not return a usable line.",
                        "Der Routingdienst hat keine nutzbare Linie geliefert.",
                    ),
                )
            }
            return GpxTrack(
                name = routeName,
                segments = segments,
                activityType = activityType,
                routeAttributes = parseRouteAttributes(features),
            )
        }

        private fun parseRouteAttributes(features: JSONArray): List<RouteAttributeSegment> {
            val parsed = buildList {
                for (featureIndex in 0 until features.length()) {
                    val messages = features.optJSONObject(featureIndex)
                        ?.optJSONObject("properties")
                        ?.optJSONArray("messages")
                        ?: continue
                    if (messages.length() < 2) continue
                    val header = messages.optJSONArray(0) ?: continue
                    val distanceIndex = header.indexOfString("Distance")
                    val wayTagsIndex = header.indexOfString("WayTags")
                    if (distanceIndex < 0 || wayTagsIndex < 0) continue
                    for (messageIndex in 1 until messages.length()) {
                        val message = messages.optJSONArray(messageIndex) ?: continue
                        val distance = message.optString(distanceIndex).toDoubleOrNull() ?: continue
                        val tags = parseWayTags(message.optString(wayTagsIndex))
                        RouteAttributeClassifier.classify(distance, tags)?.let(::add)
                    }
                }
            }
            return RouteAttributeClassifier.mergeAdjacent(parsed)
        }

        private fun JSONArray.indexOfString(value: String): Int {
            for (index in 0 until length()) {
                if (optString(index).equals(value, ignoreCase = true)) return index
            }
            return -1
        }

        private fun parseWayTags(encoded: String): Map<String, String> = encoded
            .split(' ')
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0 || separator == token.lastIndex) null
                else token.substring(0, separator) to token.substring(separator + 1)
            }
            .toMap()

        private fun parseCoordinates(coordinates: JSONArray?): List<TrackPoint> {
            if (coordinates == null) return emptyList()
            return buildList {
                for (index in 0 until coordinates.length()) {
                    val coordinate = coordinates.optJSONArray(index) ?: continue
                    if (coordinate.length() < 2) continue
                    val longitude = coordinate.optDouble(0, Double.NaN)
                    val latitude = coordinate.optDouble(1, Double.NaN)
                    if (!longitude.isFinite() || !latitude.isFinite()) continue
                    val elevation = coordinate.optDouble(2, Double.NaN).takeIf(Double::isFinite)
                    add(TrackPoint(latitude, longitude, elevationMeters = elevation))
                }
            }
        }

        private fun profileFor(activityType: ActivityType): String = when (activityType) {
            ActivityType.HIKING,
            ActivityType.RUNNING,
            -> "hiking-beta"
            ActivityType.CYCLING,
            ActivityType.E_BIKE,
            -> "trekking"
        }

        private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

        private fun localized(language: AppLanguage, english: String, german: String): String =
            if (language == AppLanguage.GERMAN) german else english

        private fun String.summarySuffix(): String {
            val summary = lineSequence().firstOrNull()?.trim()?.take(160).orEmpty()
            return if (summary.isEmpty()) "" else ": $summary"
        }
    }
}
