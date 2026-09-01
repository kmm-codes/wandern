package de.wandern.app.data

import android.content.Context
import de.wandern.app.localization.localizedSystemText
import de.wandern.app.model.DetourRouteCandidate
import de.wandern.app.model.GpxTrack
import org.json.JSONObject
import java.io.File

data class ActiveDetour(
    val sessionId: Long,
    val originalRouteReference: String?,
    val restoresRecordingRoute: Boolean,
    val route: GpxTrack,
    val corridorStartMeters: Double,
    val corridorEndMeters: Double,
    val rejoinDistanceMeters: Double,
    val extraDistanceMeters: Double,
    val directToDestination: Boolean,
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
    ): ActiveDetour {
        require(originalRouteReference != null || restoresRecordingRoute) {
            localizedSystemText(
                "A base route is required for the detour.",
                "Für die Umleitung wird eine Ausgangsroute benötigt.",
            )
        }
        val target = routeFile(sessionId)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(GpxCodec.encode(candidate.track), Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) {
            localizedSystemText(
                "Could not save detour route.",
                "Umleitungsroute konnte nicht gespeichert werden.",
            )
        }
        preferences.edit().putString(
            key(sessionId),
            JSONObject().apply {
                originalRouteReference?.let { put("original_route_reference", it) }
                put("restores_recording_route", restoresRecordingRoute)
                put("corridor_start", corridorStartMeters)
                put("corridor_end", corridorEndMeters)
                put("rejoin_distance", candidate.rejoinDistanceMeters)
                put("extra_distance", candidate.extraDistanceMeters)
                put("direct_to_destination", candidate.directToDestination)
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
        val original = metadata.optString("original_route_reference").takeIf(String::isNotBlank)
        val restoresRecordingRoute = metadata.optBoolean("restores_recording_route", false)
        if (original == null && !restoresRecordingRoute) return null
        return ActiveDetour(
            sessionId = sessionId,
            originalRouteReference = original,
            restoresRecordingRoute = restoresRecordingRoute,
            route = route,
            corridorStartMeters = metadata.optDouble("corridor_start", 0.0),
            corridorEndMeters = metadata.optDouble("corridor_end", 0.0),
            rejoinDistanceMeters = metadata.optDouble("rejoin_distance", 0.0),
            extraDistanceMeters = metadata.optDouble("extra_distance", 0.0),
            directToDestination = metadata.optBoolean("direct_to_destination", false),
        )
    }

    @Synchronized
    fun clear(sessionId: Long) {
        preferences.edit().remove(key(sessionId)).commit()
        routeFile(sessionId).delete()
        File(directory, "${routeFile(sessionId).name}.tmp").delete()
    }

    private fun routeFile(sessionId: Long) = File(directory, "detour-$sessionId.gpx")
    private fun key(sessionId: Long) = "session_$sessionId"

    private companion object {
        const val PREFERENCES = "active_navigation_detours"
    }
}
