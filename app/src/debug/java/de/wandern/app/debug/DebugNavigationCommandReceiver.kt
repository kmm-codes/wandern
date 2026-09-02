package de.wandern.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import de.wandern.app.data.DetourSessionStore
import de.wandern.app.data.OnlineRoutingClient
import de.wandern.app.data.PlaceSearchClient
import de.wandern.app.data.RecordingRouteStore
import de.wandern.app.data.TrackStore
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.GeoMath
import de.wandern.app.model.NavigationManeuverGenerator
import de.wandern.app.model.NavigationSimulation
import de.wandern.app.model.RecordingState
import de.wandern.app.model.RoutePath
import de.wandern.app.model.SimulatedLocationSample
import de.wandern.app.model.SimulationTurnDirection
import de.wandern.app.model.TrackPoint
import de.wandern.app.service.TrackingService
import de.wandern.app.ui.DetourPlannerActivity
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Debug-build-only semantic CLI endpoint. No release manifest exports this receiver. */
class DebugNavigationCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_NAVIGATION) return
        val pendingResult = goAsync()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty().ifBlank {
            System.currentTimeMillis().toString()
        }
        writeStatus(context, status("running", requestId, intent.getStringExtra(EXTRA_COMMAND)))
        thread(name = "wandern-debug-navigation") {
            val result = runCatching { execute(context.applicationContext, intent, requestId) }
                .getOrElse { error ->
                    status("error", requestId, intent.getStringExtra(EXTRA_COMMAND)).apply {
                        put("message", error.localizedMessage ?: error.javaClass.simpleName)
                    }
                }
            writeStatus(context, result)
            pendingResult.resultData = result.toString()
            pendingResult.finish()
        }
    }

    private fun execute(context: Context, intent: Intent, requestId: String): JSONObject {
        val command = intent.getStringExtra(EXTRA_COMMAND)?.trim()?.lowercase()
            ?: error("Missing debug navigation command.")
        return when (command) {
            "plan" -> plan(context, intent, requestId)
            "fixture" -> fixture(context, requestId)
            "follow" -> move(context, intent, requestId, Movement.FOLLOW)
            "deviate" -> move(context, intent, requestId, Movement.DEVIATE)
            "rejoin" -> move(context, intent, requestId, Movement.REJOIN)
            "open-rejoin" -> openRejoin(context, requestId)
            "pause" -> trackingAction(context, TrackingService.ACTION_PAUSE, requestId, command)
            "resume" -> trackingAction(context, TrackingService.ACTION_RESUME, requestId, command)
            "finish" -> trackingAction(context, TrackingService.ACTION_STOP, requestId, command)
            "discard" -> trackingAction(context, TrackingService.ACTION_DISCARD, requestId, command)
            "status" -> currentStatus(context, requestId, command)
            else -> error("Unknown debug navigation command '$command'.")
        }
    }

    private fun plan(context: Context, intent: Intent, requestId: String): JSONObject {
        check(TrackStore(context).activeSession() == null) {
            "An active recording exists. Finish or discard it before planning a simulation."
        }
        val startQuery = intent.requireBase64String(EXTRA_START_QUERY_BASE64)
        val destinationQuery = intent.requireBase64String(EXTRA_DESTINATION_QUERY_BASE64)
        val activityType = ActivityType.fromStoredValue(intent.getStringExtra(EXTRA_ACTIVITY_TYPE))
        val search = PlaceSearchClient()
        val start = search.search(startQuery).firstOrNull()
            ?: error("No place found for '$startQuery'.")
        val startPoint = TrackPoint(start.latitude, start.longitude)
        val destination = search.search(destinationQuery, startPoint).firstOrNull()
            ?: error("No place found for '$destinationQuery'.")
        val destinationPoint = TrackPoint(destination.latitude, destination.longitude)
        val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME).orEmpty().ifBlank {
            "Simulation · ${start.displayName.substringBefore(',')} – ${destination.displayName.substringBefore(',')}"
        }
        val route = OnlineRoutingClient().calculate(
            waypoints = listOf(startPoint, destinationPoint),
            activityType = activityType,
            routeName = routeName,
        )
        val stored = saveRoute(
            context = context,
            track = route,
            routeControlPoints = listOf(
                TrackStore.RouteControlPoint(startPoint, start.displayName),
                TrackStore.RouteControlPoint(destinationPoint, destination.displayName),
            ),
        )
        return currentStatus(context, requestId, "plan").apply {
            put("routeReference", stored.reference)
            put("routeName", route.name)
            put("activityType", activityType.name)
            put("routeDistanceMeters", RoutePath(route).totalDistanceMeters)
            put("start", start.displayName)
            put("destination", destination.displayName)
        }
    }

    private fun fixture(context: Context, requestId: String): JSONObject {
        check(TrackStore(context).activeSession() == null) {
            "An active recording exists. Finish or discard it before preparing a fixture."
        }
        val points = (0..160).map { index ->
            val fraction = index / 160.0
            TrackPoint(
                latitude = 48.805 - fraction * 0.105 + kotlin.math.sin(fraction * Math.PI * 5.0) * 0.004,
                longitude = 8.205 + fraction * 0.075 + kotlin.math.sin(fraction * Math.PI * 4.0) * 0.008,
                elevationMeters = 135.0 + fraction * 480.0 + kotlin.math.sin(fraction * Math.PI * 6.0) * 45.0,
            )
        }
        val route = NavigationManeuverGenerator.withGeneratedManeuvers(
            GpxTrack(
                name = "Simulation · Schwarzwald",
                segments = listOf(points),
                activityType = ActivityType.HIKING,
            ),
        )
        val stored = saveRoute(
            context = context,
            track = route,
            routeControlPoints = listOf(
                TrackStore.RouteControlPoint(points.first(), "Simulation start"),
                TrackStore.RouteControlPoint(points.last(), "Simulation destination"),
            ),
        )
        return currentStatus(context, requestId, "fixture").apply {
            put("routeReference", stored.reference)
            put("routeName", route.name)
            put("activityType", ActivityType.HIKING.name)
            put("routeDistanceMeters", RoutePath(route).totalDistanceMeters)
        }
    }

    private fun saveRoute(
        context: Context,
        track: GpxTrack,
        routeControlPoints: List<TrackStore.RouteControlPoint>,
    ): TrackStore.StoredTour {
        return TrackStore(context).saveImportedTrack(
            track = track,
            plannedSource = TrackStore.PlannedTourSource.ROUTER,
            routeControlPoints = routeControlPoints,
        )
    }

    private fun move(
        context: Context,
        intent: Intent,
        requestId: String,
        movement: Movement,
    ): JSONObject {
        val store = TrackStore(context)
        val session = store.activeSession() ?: error("No active recording exists.")
        check(session.state == RecordingState.RECORDING) { "The recording is not running." }
        val route = activeRoute(context, session)
        val recorded = store.loadTrack(session.id)
        val progress = NavigationSimulation.routeProgress(route, recorded)
        val speed = intent.doubleExtra(EXTRA_SPEED_KMH, DEFAULT_SPEED_KMH)
        val step = intent.doubleExtra(EXTRA_STEP_METERS, DEFAULT_STEP_METERS)
        val samples = when (movement) {
            Movement.FOLLOW -> NavigationSimulation.followRoute(
                route = route,
                fromDistanceMeters = progress,
                distanceMeters = intent.doubleExtra(EXTRA_DISTANCE_METERS, DEFAULT_FOLLOW_METERS),
                speedKilometersPerHour = speed,
                stepMeters = step,
            )
            Movement.DEVIATE -> NavigationSimulation.deviateAtNextTurn(
                route = route,
                fromDistanceMeters = progress,
                direction = when (intent.getStringExtra(EXTRA_DIRECTION)?.lowercase()) {
                    "left" -> SimulationTurnDirection.LEFT
                    else -> SimulationTurnDirection.RIGHT
                },
                deviationDistanceMeters = intent.doubleExtra(EXTRA_DISTANCE_METERS, DEFAULT_DEVIATION_METERS),
                speedKilometersPerHour = speed,
                stepMeters = step,
            )
            Movement.REJOIN -> NavigationSimulation.rejoinRoute(
                route = route,
                currentPosition = recorded.points.lastOrNull() ?: error("The recording contains no current position."),
                progressAnchorMeters = progress,
                speedKilometersPerHour = speed,
                stepMeters = step,
            )
        }
        val beforePoints = recorded.points.size
        sendSamples(context, samples)
        val destination = samples.last().point
        waitFor("simulated locations") {
            val updated = TrackStore(context).loadTrack(session.id)
            updated.points.size > beforePoints &&
                updated.points.lastOrNull()?.let { GeoMath.distanceMeters(it, destination) < 2.0 } == true
        }
        return currentStatus(context, requestId, movement.command).apply {
            put("generatedSamples", samples.size)
            put("requestedSpeedKmh", speed)
            put(
                "requestedDistanceMeters",
                if (movement == Movement.REJOIN) JSONObject.NULL
                else intent.doubleExtra(EXTRA_DISTANCE_METERS, DEFAULT_FOLLOW_METERS),
            )
        }
    }

    private fun openRejoin(context: Context, requestId: String): JSONObject {
        val store = TrackStore(context)
        val session = store.activeSession() ?: error("No active recording exists.")
        val route = activeRoute(context, session)
        val recorded = store.loadTrack(session.id)
        val point = recorded.points.lastOrNull() ?: error("The recording contains no current position.")
        val progress = NavigationSimulation.routeProgress(route, recorded)
        context.startActivity(
            Intent(context, DetourPlannerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(DetourPlannerActivity.EXTRA_SESSION_ID, session.id)
                .putExtra(DetourPlannerActivity.EXTRA_LATITUDE, point.latitude)
                .putExtra(DetourPlannerActivity.EXTRA_LONGITUDE, point.longitude)
                .putExtra(DetourPlannerActivity.EXTRA_PROGRESS_METERS, progress)
                .putExtra(DetourPlannerActivity.EXTRA_MODE, DetourPlannerActivity.MODE_REJOIN),
        )
        return currentStatus(context, requestId, "open-rejoin").apply {
            put("planner", "rejoin")
        }
    }

    private fun trackingAction(
        context: Context,
        action: String,
        requestId: String,
        command: String,
    ): JSONObject {
        context.startService(Intent(context, TrackingService::class.java).setAction(action))
        Thread.sleep(250L)
        return currentStatus(context, requestId, command)
    }

    private fun sendSamples(context: Context, samples: List<SimulatedLocationSample>) {
        val points = samples.map(SimulatedLocationSample::point)
        context.startService(
            Intent(context, TrackingService::class.java)
                .setAction(TrackingService.ACTION_DEBUG_SIMULATE_LOCATIONS)
                .putExtra(TrackingService.EXTRA_DEBUG_LATITUDES, points.map(TrackPoint::latitude).toDoubleArray())
                .putExtra(TrackingService.EXTRA_DEBUG_LONGITUDES, points.map(TrackPoint::longitude).toDoubleArray())
                .putExtra(
                    TrackingService.EXTRA_DEBUG_ELEVATIONS,
                    points.map { it.elevationMeters ?: Double.NaN }.toDoubleArray(),
                )
                .putExtra(
                    TrackingService.EXTRA_DEBUG_ELAPSED_MILLIS,
                    samples.map(SimulatedLocationSample::elapsedMillis).toLongArray(),
                )
                .putExtra(
                    TrackingService.EXTRA_DEBUG_SPEEDS,
                    points.map { it.speedMetersPerSecond ?: Float.NaN }.toFloatArray(),
                )
                .putExtra(
                    TrackingService.EXTRA_DEBUG_BEARINGS,
                    points.map { it.bearingDegrees ?: Float.NaN }.toFloatArray(),
                )
                .putExtra(TrackingService.EXTRA_DEBUG_ACCURACY_METERS, 6f),
        )
    }

    private fun activeRoute(context: Context, session: TrackStore.SessionInfo): GpxTrack {
        return DetourSessionStore(context).load(session.id)?.route
            ?: RecordingRouteStore(context).load(session.id)?.route
            ?: session.routeReference?.let { TrackStore(context).loadStoredTrack(it) }
            ?: error("The active recording has no navigation route.")
    }

    private fun currentStatus(context: Context, requestId: String, command: String): JSONObject {
        val snapshot = TrackingService.snapshots.value
        val session = TrackStore(context).activeSession()
        return status("ok", requestId, command).apply {
            put("recordingState", snapshot.state.name.lowercase())
            put("sessionId", session?.id ?: JSONObject.NULL)
            put("routeReference", session?.routeReference ?: JSONObject.NULL)
            put("distanceMeters", snapshot.stats.distanceMeters)
            put("movingDurationMillis", snapshot.stats.movingDurationMillis)
            put("currentSpeedKmh", snapshot.latestPoint?.speedMetersPerSecond?.times(3.6f) ?: JSONObject.NULL)
            put("pointCount", snapshot.track.points.size)
            put("routeDeviationMeters", snapshot.routeDeviationMeters ?: JSONObject.NULL)
            put("confirmedOffRoute", snapshot.confirmedOffRoute)
            put("autoPaused", snapshot.autoPaused)
            put("navigationDistanceMeters", snapshot.navigationGuidance?.distanceMeters ?: JSONObject.NULL)
            put("navigationManeuver", snapshot.navigationGuidance?.maneuver?.type?.name ?: JSONObject.NULL)
            snapshot.latestPoint?.let { point ->
                put("latitude", point.latitude)
                put("longitude", point.longitude)
                put("accuracyMeters", point.accuracyMeters ?: JSONObject.NULL)
            }
        }
    }

    private fun status(phase: String, requestId: String, command: String?): JSONObject = JSONObject().apply {
        put("phase", phase)
        put("requestId", requestId)
        put("command", command ?: JSONObject.NULL)
        put("updatedAtMillis", System.currentTimeMillis())
    }

    private fun writeStatus(context: Context, value: JSONObject) {
        val directory = File(context.filesDir, STATUS_DIRECTORY).apply { mkdirs() }
        File(directory, STATUS_FILE).writeText(value.toString(2), Charsets.UTF_8)
    }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50L)
        }
        error("Timed out waiting for $label.")
    }

    private fun Intent.requireBase64String(name: String): String = getStringExtra(name)
        ?.let { encoded -> String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error("Missing '$name'.")

    private fun Intent.doubleExtra(name: String, defaultValue: Double): Double =
        getStringExtra(name)?.toDoubleOrNull() ?: getDoubleExtra(name, defaultValue)

    private enum class Movement(val command: String) {
        FOLLOW("follow"),
        DEVIATE("deviate"),
        REJOIN("rejoin"),
    }

    companion object {
        const val ACTION_DEBUG_NAVIGATION = "de.wandern.app.DEBUG_NAVIGATION"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_START_QUERY_BASE64 = "start_query_base64"
        const val EXTRA_DESTINATION_QUERY_BASE64 = "destination_query_base64"
        const val EXTRA_ROUTE_NAME = "route_name"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_DISTANCE_METERS = "distance_meters"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_STEP_METERS = "step_meters"
        const val EXTRA_DIRECTION = "direction"

        private const val STATUS_DIRECTORY = "debug-navigation"
        private const val STATUS_FILE = "status.json"
        private const val COMMAND_TIMEOUT_MILLIS = 20_000L
        private const val DEFAULT_SPEED_KMH = 5.0
        private const val DEFAULT_STEP_METERS = 10.0
        private const val DEFAULT_FOLLOW_METERS = 1_000.0
        private const val DEFAULT_DEVIATION_METERS = 500.0
    }
}
