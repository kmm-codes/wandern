package de.wandern.app.data

import android.content.Context
import de.wandern.app.localization.localizedSystemText
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ActiveRecordingRoute(
    val sessionId: Long,
    val route: GpxTrack,
    val controlPoints: List<TrackStore.RouteControlPoint>,
)

/**
 * Stores a navigation-only route override for an active recording.
 *
 * The route deliberately lives outside [TrackStore]: editing it must never mutate the saved tour
 * referenced by the recording. It is removed when the recording is finished or discarded.
 */
class RecordingRouteStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "navigation").apply { mkdirs() }

    @Synchronized
    fun save(
        sessionId: Long,
        route: GpxTrack,
        controlPoints: List<TrackStore.RouteControlPoint>,
    ): ActiveRecordingRoute {
        require(route.points.size >= 2) {
            localizedSystemText(
                "The navigation route needs at least two points.",
                "Die Navigationsroute benötigt mindestens zwei Punkte.",
            )
        }
        val routeTarget = routeFile(sessionId)
        val routeTemporary = File(directory, "${routeTarget.name}.tmp")
        val metadataTarget = metadataFile(sessionId)
        val metadataTemporary = File(directory, "${metadataTarget.name}.tmp")
        routeTemporary.writeText(GpxCodec.encode(route), Charsets.UTF_8)
        metadataTemporary.writeText(encodeControlPoints(controlPoints), Charsets.UTF_8)
        replace(routeTemporary, routeTarget)
        replace(metadataTemporary, metadataTarget)
        return load(sessionId) ?: error(
            localizedSystemText(
                "Could not reopen the active navigation route.",
                "Aktive Navigationsroute konnte nicht wieder geöffnet werden.",
            ),
        )
    }

    @Synchronized
    fun load(sessionId: Long): ActiveRecordingRoute? {
        val routeTarget = routeFile(sessionId).takeIf(File::isFile) ?: return null
        val route = runCatching {
            routeTarget.inputStream().use {
                GpxCodec.parse(it, localizedSystemText("Active route", "Aktive Route"))
            }
        }.getOrNull() ?: return null
        val controlPoints = runCatching {
            metadataFile(sessionId).takeIf(File::isFile)?.readText(Charsets.UTF_8)
                ?.let(::decodeControlPoints)
        }.getOrNull().orEmpty()
        return ActiveRecordingRoute(sessionId, route, controlPoints)
    }

    @Synchronized
    fun clear(sessionId: Long) {
        listOf(routeFile(sessionId), metadataFile(sessionId)).forEach { target ->
            target.delete()
            File(directory, "${target.name}.tmp").delete()
        }
    }

    private fun replace(temporary: File, target: File) {
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) {
            localizedSystemText(
                "Could not save the active navigation route.",
                "Aktive Navigationsroute konnte nicht gespeichert werden.",
            )
        }
    }

    private fun encodeControlPoints(points: List<TrackStore.RouteControlPoint>): String =
        JSONArray().apply {
            points.forEach { control ->
                put(JSONObject().apply {
                    put("latitude", control.point.latitude)
                    put("longitude", control.point.longitude)
                    control.point.elevationMeters?.let { put("elevation", it) }
                    control.label?.let { put("label", it) }
                })
            }
        }.toString()

    private fun decodeControlPoints(encoded: String): List<TrackStore.RouteControlPoint> {
        val array = JSONArray(encoded)
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                val latitude = value.optDouble("latitude", Double.NaN)
                val longitude = value.optDouble("longitude", Double.NaN)
                if (!latitude.isFinite() || !longitude.isFinite()) continue
                add(
                    TrackStore.RouteControlPoint(
                        point = TrackPoint(
                            latitude = latitude,
                            longitude = longitude,
                            elevationMeters = value.optDouble("elevation", Double.NaN)
                                .takeIf(Double::isFinite),
                        ),
                        label = value.optString("label").takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }

    private fun routeFile(sessionId: Long) = File(directory, "recording-route-$sessionId.gpx")
    private fun metadataFile(sessionId: Long) = File(directory, "recording-route-$sessionId.json")
}
