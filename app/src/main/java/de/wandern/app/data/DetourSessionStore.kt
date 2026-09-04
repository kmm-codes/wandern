package de.wandern.app.data

import android.content.Context
import de.wandern.app.localization.localizedSystemText
import de.wandern.app.model.DetourRouteCandidate
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RouteAdjustmentKind
import de.wandern.app.model.RouteClosure
import de.wandern.app.model.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ActiveDetour(
    val sessionId: Long,
    val originalRouteReference: String?,
    val restoresRecordingRoute: Boolean,
    val route: GpxTrack,
    val detourTrack: GpxTrack?,
    val departureDistanceMeters: Double,
    val corridorStartMeters: Double,
    val corridorEndMeters: Double,
    val rejoinDistanceMeters: Double,
    val extraDistanceMeters: Double,
    val directToDestination: Boolean,
    val kind: RouteAdjustmentKind,
)

class DetourSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, "navigation").apply { mkdirs() }

    @Synchronized
    fun save(
        sessionId: Long,
        originalRouteReference: String?,
        candidate: DetourRouteCandidate,
        corridorStartMeters: Double,
        corridorEndMeters: Double,
        restoresRecordingRoute: Boolean = false,
        kind: RouteAdjustmentKind = RouteAdjustmentKind.DETOUR,
    ): ActiveDetour {
        require(originalRouteReference != null || restoresRecordingRoute) {
            localizedSystemText(
                "A base route is required for the detour.",
                "Für die Umleitung wird eine Ausgangsroute benötigt.",
            )
        }
        val target = routeFile(sessionId)
        val temporary = File(directory, "${target.name}.tmp")
        val detourTarget = detourFile(sessionId)
        val detourTemporary = File(directory, "${detourTarget.name}.tmp")
        temporary.writeText(GpxCodec.encode(candidate.track), Charsets.UTF_8)
        detourTemporary.writeText(GpxCodec.encode(candidate.detourTrack), Charsets.UTF_8)
        replace(temporary, target)
        replace(detourTemporary, detourTarget)
        preferences.edit().putString(
            key(sessionId),
            JSONObject().apply {
                originalRouteReference?.let { put("original_route_reference", it) }
                put("restores_recording_route", restoresRecordingRoute)
                put("departure_distance", candidate.departureDistanceMeters)
                put("corridor_start", corridorStartMeters)
                put("corridor_end", corridorEndMeters)
                put("rejoin_distance", candidate.rejoinDistanceMeters)
                put("extra_distance", candidate.extraDistanceMeters)
                put("direct_to_destination", candidate.directToDestination)
                put("kind", kind.name)
            }.toString(),
        ).commit()
        return load(sessionId) ?: error(
            localizedSystemText(
                "Could not reopen the saved detour.",
                "Gespeicherte Umleitung konnte nicht wieder geöffnet werden.",
            ),
        )
    }

    @Synchronized
    fun load(sessionId: Long): ActiveDetour? {
        val metadata = preferences.getString(key(sessionId), null)?.let(::JSONObject) ?: return null
        val file = routeFile(sessionId).takeIf(File::isFile) ?: return null
        val route = runCatching {
            file.inputStream().use {
                GpxCodec.parse(it, localizedSystemText("Active detour", "Aktive Umleitung"))
            }
        }
            .getOrNull() ?: return null
        val detourTrack = detourFile(sessionId).takeIf(File::isFile)?.let { target ->
            runCatching {
                target.inputStream().use {
                    GpxCodec.parse(it, localizedSystemText("Detour segment", "Umleitungsstück"))
                }
            }.getOrNull()
        }
        val original = metadata.optString("original_route_reference").takeIf(String::isNotBlank)
        val restoresRecordingRoute = metadata.optBoolean("restores_recording_route", false)
        if (original == null && !restoresRecordingRoute) return null
        return ActiveDetour(
            sessionId = sessionId,
            originalRouteReference = original,
            restoresRecordingRoute = restoresRecordingRoute,
            route = route,
            detourTrack = detourTrack,
            departureDistanceMeters = metadata.optDouble("departure_distance", 0.0),
            corridorStartMeters = metadata.optDouble("corridor_start", 0.0),
            corridorEndMeters = metadata.optDouble("corridor_end", 0.0),
            rejoinDistanceMeters = metadata.optDouble("rejoin_distance", 0.0),
            extraDistanceMeters = metadata.optDouble("extra_distance", 0.0),
            directToDestination = metadata.optBoolean("direct_to_destination", false),
            kind = metadata.optString("kind")
                .let { stored -> runCatching { RouteAdjustmentKind.valueOf(stored) }.getOrNull() }
                ?: RouteAdjustmentKind.DETOUR,
        )
    }

    /** Sections the hiker reported as blocked during this recording, oldest first. */
    @Synchronized
    fun closures(sessionId: Long): List<RouteClosure> {
        val file = closureFile(sessionId).takeIf(File::isFile) ?: return emptyList()
        return runCatching { decodeClosures(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    /** Adds a closure that stays active for the rest of the recording. */
    @Synchronized
    fun addClosure(sessionId: Long, closure: RouteClosure): List<RouteClosure> {
        if (closure.points.size < 2) return closures(sessionId)
        val updated = (closures(sessionId).filterNot { it.id == closure.id } + closure)
            .takeLast(MAX_CLOSURES)
        val target = closureFile(sessionId)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(encodeClosures(updated), Charsets.UTF_8)
        replace(temporary, target)
        return updated
    }

    /**
     * Drops everything this recording session stored: the active detour and the reported closures.
     *
     * Used when the recording itself ends, is discarded or when the hiker removes the detour, which
     * is deliberately all-or-nothing. A route change during the recording must call [clearDetour]
     * instead, because the closures describe the terrain and not the planned route.
     */
    @Synchronized
    fun clear(sessionId: Long) {
        clearDetour(sessionId)
        delete(closureFile(sessionId))
    }

    /**
     * Drops only the active detour and keeps the reported closures of this recording session.
     *
     * A hiker who plans a new route mid-recording leaves the detour behind, but the sections they
     * reported as blocked stay blocked for every later routing request of the same recording.
     */
    @Synchronized
    fun clearDetour(sessionId: Long) {
        preferences.edit().remove(key(sessionId)).commit()
        listOf(routeFile(sessionId), detourFile(sessionId)).forEach(::delete)
    }

    private fun delete(target: File) {
        target.delete()
        File(directory, "${target.name}.tmp").delete()
    }

    private fun encodeClosures(closures: List<RouteClosure>): String = JSONObject().apply {
        put(
            "closures",
            JSONArray().apply {
                closures.forEach { closure ->
                    put(
                        JSONObject().apply {
                            put("id", closure.id)
                            put("created_at", closure.createdAtMillis)
                            put("width_meters", closure.widthMeters)
                            put(
                                "points",
                                JSONArray().apply {
                                    closure.points.forEach { point ->
                                        put(
                                            JSONObject().apply {
                                                put("lat", point.latitude)
                                                put("lon", point.longitude)
                                                point.elevationMeters?.let { put("ele", it) }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    private fun decodeClosures(encoded: String): List<RouteClosure> {
        val entries = JSONObject(encoded).optJSONArray("closures") ?: return emptyList()
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val encodedPoints = entry.optJSONArray("points") ?: continue
                val points = buildList {
                    for (pointIndex in 0 until encodedPoints.length()) {
                        val point = encodedPoints.optJSONObject(pointIndex) ?: continue
                        val latitude = point.optDouble("lat", Double.NaN)
                        val longitude = point.optDouble("lon", Double.NaN)
                        if (!latitude.isFinite() || !longitude.isFinite()) continue
                        add(
                            TrackPoint(
                                latitude = latitude,
                                longitude = longitude,
                                elevationMeters = point.optDouble("ele", Double.NaN).takeIf(Double::isFinite),
                            ),
                        )
                    }
                }
                if (points.size < 2) continue
                add(
                    RouteClosure(
                        id = entry.optLong("id"),
                        createdAtMillis = entry.optLong("created_at"),
                        widthMeters = entry.optInt("width_meters", DEFAULT_CLOSURE_WIDTH_METERS),
                        points = points,
                    ),
                )
            }
        }
    }

    private fun replace(temporary: File, target: File) {
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) {
            localizedSystemText(
                "Could not save detour route.",
                "Umleitungsroute konnte nicht gespeichert werden.",
            )
        }
    }

    private fun routeFile(sessionId: Long) = File(directory, "detour-$sessionId.gpx")
    private fun detourFile(sessionId: Long) = File(directory, "detour-segment-$sessionId.gpx")
    private fun closureFile(sessionId: Long) = File(directory, "closures-$sessionId.json")
    private fun key(sessionId: Long) = "session_$sessionId"

    private companion object {
        const val PREFERENCES = "active_navigation_detours"
        const val MAX_CLOSURES = 20
        const val DEFAULT_CLOSURE_WIDTH_METERS = 30
    }
}
