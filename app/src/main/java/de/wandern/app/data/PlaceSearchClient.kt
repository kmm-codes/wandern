package de.wandern.app.data

import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

data class PlaceSearchResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

class PlaceSearchClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URI(url).toURL().openConnection() as HttpURLConnection
    },
    private val reverseEndpoint: String = DEFAULT_REVERSE_ENDPOINT,
    private val language: AppLanguage = AppLanguage.forSystem(),
) {
    private val cache = object : LinkedHashMap<String, List<PlaceSearchResult>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<PlaceSearchResult>>?,
        ): Boolean = size > CACHE_SIZE
    }
    private var lastRequestStartedAt = 0L

    @Synchronized
    fun search(query: String, reference: TrackPoint? = null): List<PlaceSearchResult> {
        val normalizedQuery = query.trim()
        require(normalizedQuery.length >= MIN_QUERY_LENGTH) {
            localized("Enter at least three characters.", "Bitte gib mindestens drei Zeichen ein.")
        }
        val biasKey = reference?.let {
            "${(it.latitude * 1_000).toInt()}:${(it.longitude * 1_000).toInt()}"
        } ?: "global"
        val cacheKey = "${language.tag}:$biasKey:${normalizedQuery.lowercase()}"
        cache[cacheKey]?.let { return it }

        val waitMillis = REQUEST_INTERVAL_MILLIS - (System.currentTimeMillis() - lastRequestStartedAt)
        if (waitMillis > 0) Thread.sleep(waitMillis)
        lastRequestStartedAt = System.currentTimeMillis()

        val connection = connectionFactory(
            buildRequestUrl(endpoint, normalizedQuery, language, reference),
        ).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", language.tag)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException(
                    localized(
                        "Place search returned HTTP $status",
                        "Ortssuche antwortet mit HTTP $status",
                    ),
                )
            }
            return parseResults(body).also { cache[cacheKey] = it }
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    fun reverse(latitude: Double, longitude: Double): PlaceSearchResult? {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        waitForRequestSlot()
        val connection = connectionFactory(
            buildReverseRequestUrl(reverseEndpoint, latitude, longitude, language),
        ).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", language.tag)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException(
                    localized(
                        "Place search returned HTTP $status",
                        "Ortssuche antwortet mit HTTP $status",
                    ),
                )
            }
            return parseReverseResult(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForRequestSlot() {
        val waitMillis = REQUEST_INTERVAL_MILLIS - (System.currentTimeMillis() - lastRequestStartedAt)
        if (waitMillis > 0) Thread.sleep(waitMillis)
        lastRequestStartedAt = System.currentTimeMillis()
    }

    private fun localized(english: String, german: String): String =
        if (language == AppLanguage.GERMAN) german else english

    companion object {
        const val DEFAULT_ENDPOINT = "https://photon.komoot.io/api"
        const val DEFAULT_REVERSE_ENDPOINT = "https://nominatim.openstreetmap.org/reverse"
        private const val USER_AGENT = "Wandern-Android/0.1 (+https://github.com/kmm-codes/wandern)"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val REQUEST_INTERVAL_MILLIS = 1_000L
        private const val CACHE_SIZE = 24
        private const val MIN_QUERY_LENGTH = 3

        internal fun buildRequestUrl(
            endpoint: String,
            query: String,
            language: AppLanguage = AppLanguage.forSystem(),
            reference: TrackPoint? = null,
        ): String {
            val params = linkedMapOf(
                "q" to query,
                "limit" to "5",
                "lang" to language.tag,
            )
            if (reference != null) {
                params["lat"] = reference.latitude.toString()
                params["lon"] = reference.longitude.toString()
                params["zoom"] = "12"
                params["location_bias_scale"] = "0.1"
            }
            return endpoint.trimEnd('/') + "?" + params.entries.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
        }

        internal fun buildReverseRequestUrl(
            endpoint: String,
            latitude: Double,
            longitude: Double,
            language: AppLanguage = AppLanguage.forSystem(),
        ): String {
            val params = linkedMapOf(
                "lat" to latitude.toString(),
                "lon" to longitude.toString(),
                "format" to "jsonv2",
                "zoom" to "18",
                "addressdetails" to "1",
                "accept-language" to language.tag,
            )
            return endpoint.trimEnd('/') + "?" + params.entries.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
        }

        internal fun parseResults(json: String): List<PlaceSearchResult> {
            val array = JSONObject(json).optJSONArray("features") ?: JSONArray()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val coordinates = item.optJSONObject("geometry")
                        ?.optJSONArray("coordinates") ?: continue
                    val longitude = coordinates.optDouble(0, Double.NaN)
                    val latitude = coordinates.optDouble(1, Double.NaN)
                    val properties = item.optJSONObject("properties") ?: continue
                    val name = photonDisplayName(properties)
                    if (name.isEmpty() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                        continue
                    }
                    add(PlaceSearchResult(name, latitude, longitude))
                }
            }
        }

        private fun photonDisplayName(properties: JSONObject): String {
            fun value(key: String): String = properties.optString(key).trim()
            val street = value("street")
            val houseNumber = value("housenumber")
            val primary = value("name").ifEmpty {
                listOf(street, houseNumber).filter(String::isNotEmpty).joinToString(" ")
            }.ifEmpty {
                listOf(value("district"), value("city"), value("county"))
                    .firstOrNull(String::isNotEmpty).orEmpty()
            }
            if (primary.isEmpty()) return ""
            val context = listOf(
                value("district"),
                value("city"),
                value("county"),
                value("state"),
                value("country"),
            ).filter(String::isNotEmpty)
                .filterNot { it.equals(primary, ignoreCase = true) }
                .distinctBy(String::lowercase)
            return (listOf(primary) + context).joinToString(", ")
        }

        internal fun parseReverseResult(json: String): PlaceSearchResult? {
            val item = JSONObject(json)
            val latitude = item.optString("lat").toDoubleOrNull() ?: return null
            val longitude = item.optString("lon").toDoubleOrNull() ?: return null
            val name = item.optString("display_name").trim()
            if (name.isEmpty() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return PlaceSearchResult(name, latitude, longitude)
        }

        private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())
    }
}
