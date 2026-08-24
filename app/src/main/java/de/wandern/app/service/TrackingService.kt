package de.wandern.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.AutoPauseDetector
import de.wandern.app.model.AutoPauseTransition
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpsGapInterpolator
import de.wandern.app.model.GpsQuality
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RecordingState
import de.wandern.app.model.RouteDeviationEvent
import de.wandern.app.model.RouteDeviationMonitor
import de.wandern.app.model.TrackAnalyzer
import de.wandern.app.model.TrackPoint
import de.wandern.app.model.TrackingSnapshot
import de.wandern.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var trackStore: TrackStore
    private var sessionId: Long? = null
    private var segmentIndex = 0
    private var lastAcceptedPoint: TrackPoint? = null
    private var lastObservedPoint: TrackPoint? = null
    private var gpsGapActive = false
    private var autoPaused = false
    private val autoPauseDetector = AutoPauseDetector()
    private var activityType = ActivityType.HIKING
    private var activeRoute: GpxTrack? = null
    private var routeDeviationMeters: Double? = null
    private var confirmedOffRoute = false
    private val routeDeviationMonitor = RouteDeviationMonitor()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        trackStore = TrackStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                intent.getStringExtra(EXTRA_ROUTE_NAME),
                intent.getStringExtra(EXTRA_ROUTE_REFERENCE),
                ActivityType.fromStoredValue(intent.getStringExtra(EXTRA_ACTIVITY_TYPE)),
            )
            ACTION_RESTORE -> restoreAfterProcessRestart()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            ACTION_DISCARD -> discardRecording()
            else -> restoreAfterProcessRestart()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        if (_snapshots.value.state != RecordingState.RECORDING) return
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            elevationMeters = location.altitude.takeIf { location.hasAltitude() },
            timeMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            accuracyMeters = location.accuracy,
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
        )
        val observedTime = lastObservedPoint?.timeMillis
        if (observedTime != null && (point.timeMillis ?: 0L) < observedTime) return
        lastObservedPoint = point
        if (location.accuracy > GpsQuality.RELIABLE_ACCURACY_METERS) {
            gpsGapActive = !autoPaused && lastAcceptedPoint != null
            publishObservedLocation(point)
            return
        }

        updateRouteDeviation(point)

        val autoPauseUpdate = autoPauseDetector.update(point, SystemClock.elapsedRealtime())
        autoPaused = autoPauseUpdate.autoPaused
        if (autoPauseUpdate.transition == AutoPauseTransition.RESUMED) {
            // A stationary interval is not a GPS gap and must never be interpolated.
            lastAcceptedPoint = null
        }
        if ((autoPaused || autoPauseUpdate.stationaryEvidence) && lastAcceptedPoint != null) {
            gpsGapActive = false
            publishObservedLocation(point)
            if (autoPauseUpdate.transition != AutoPauseTransition.NONE) updateNotification()
            return
        }

        val previous = lastAcceptedPoint
        if (previous != null) {
            val distance = GeoMath.distanceMeters(previous, point)
            val elapsed = (point.timeMillis ?: 0L) - (previous.timeMillis ?: 0L)
            if (distance < MIN_DISTANCE_METERS && elapsed < MAX_STATIONARY_INTERVAL_MILLIS) {
                gpsGapActive = false
                publishObservedLocation(point)
                return
            }
        }

        val activeSessionId = sessionId ?: return
        runCatching {
            val interpolated = previous?.let {
                val elapsed = (point.timeMillis ?: 0L) - (it.timeMillis ?: 0L)
                if (elapsed >= GPS_GAP_THRESHOLD_MILLIS) {
                    GpsGapInterpolator.between(it, point)
                } else {
                    emptyList()
                }
            }.orEmpty()
            trackStore.appendPoints(activeSessionId, segmentIndex, interpolated + point)
            lastAcceptedPoint = point
            gpsGapActive = false
            publishSnapshot(RecordingState.RECORDING)
            updateNotification()
        }.onFailure { publishError("Punkt konnte nicht gespeichert werden: ${it.localizedMessage}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        publishError("GPS ist deaktiviert.")
    }

    override fun onDestroy() {
        removeLocationUpdates()
        super.onDestroy()
    }

    private fun startRecording(
        routeName: String?,
        routeReference: String?,
        requestedActivityType: ActivityType,
    ) {
        if (sessionId != null) return
        trackStore.activeSession()?.let {
            restoreActiveSession(it)
            return
        }
        activityType = requestedActivityType
        sessionId = trackStore.createSession(routeName, routeReference, activityType)
        segmentIndex = 0
        lastAcceptedPoint = null
        lastObservedPoint = null
        gpsGapActive = false
        autoPaused = false
        autoPauseDetector.reset()
        configureRoute(routeReference)
        startAsForeground()
        publishSnapshot(RecordingState.RECORDING)
        requestLocationUpdates()
    }

    private fun pauseRecording() {
        val id = sessionId ?: return
        removeLocationUpdates()
        gpsGapActive = false
        autoPaused = false
        autoPauseDetector.reset()
        trackStore.updateState(id, RecordingState.PAUSED)
        publishSnapshot(RecordingState.PAUSED)
        updateNotification()
    }

    private fun resumeRecording() {
        val id = sessionId ?: return
        segmentIndex += 1
        lastAcceptedPoint = null
        gpsGapActive = false
        autoPaused = false
        autoPauseDetector.reset()
        resetRouteDeviationState()
        trackStore.updateState(id, RecordingState.RECORDING, segmentIndex)
        publishSnapshot(RecordingState.RECORDING)
        startAsForeground()
        requestLocationUpdates()
        updateNotification()
    }

    private fun stopRecording() {
        val id = sessionId ?: run {
            stopSelf()
            return
        }
        removeLocationUpdates()
        val file = runCatching { trackStore.finishSession(id) }.getOrElse {
            publishError("Tour konnte nicht abgeschlossen werden: ${it.localizedMessage}")
            return
        }
        val track = trackStore.loadTrack(id)
        _snapshots.value = TrackingSnapshot(
            state = RecordingState.FINISHED,
            track = track,
            stats = TrackAnalyzer.calculate(track),
            latestPoint = lastObservedPoint ?: track.points.lastOrNull(),
            savedTrackPath = file.absolutePath,
            activityType = activityType,
        )
        sessionId = null
        activityType = ActivityType.HIKING
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun discardRecording() {
        val id = sessionId ?: run {
            stopSelf()
            return
        }
        removeLocationUpdates()
        if (!trackStore.discardSession(id)) {
            publishError("Aufzeichnung konnte nicht verworfen werden.")
            return
        }
        sessionId = null
        lastAcceptedPoint = null
        lastObservedPoint = null
        gpsGapActive = false
        autoPaused = false
        autoPauseDetector.reset()
        _snapshots.value = TrackingSnapshot()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreAfterProcessRestart() {
        val active = trackStore.activeSession() ?: run {
            stopSelf()
            return
        }
        restoreActiveSession(active)
    }

    private fun restoreActiveSession(active: TrackStore.SessionInfo) {
        sessionId = active.id
        activityType = active.activityType
        segmentIndex = active.segmentIndex
        lastAcceptedPoint = trackStore.loadTrack(active.id).segments.lastOrNull()?.lastOrNull()
        lastObservedPoint = lastAcceptedPoint
        gpsGapActive = false
        autoPaused = false
        autoPauseDetector.reset()
        configureRoute(active.routeReference)
        publishSnapshot(active.state)
        if (active.state == RecordingState.RECORDING) {
            startAsForeground()
            requestLocationUpdates()
        }
    }

    private fun requestLocationUpdates() {
        val fineGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            publishError("Standortberechtigung fehlt.")
            return
        }
        runCatching {
            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 2f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8_000L, 5f, this)
            }
        }.onFailure { publishError("Standort konnte nicht gestartet werden: ${it.localizedMessage}") }
    }

    private fun removeLocationUpdates() {
        runCatching { locationManager.removeUpdates(this) }
    }

    private fun publishSnapshot(state: RecordingState) {
        val id = sessionId ?: return
        val track = trackStore.loadTrack(id)
        _snapshots.value = TrackingSnapshot(
            state = state,
            track = track,
            stats = TrackAnalyzer.calculate(track),
            latestPoint = lastObservedPoint ?: track.points.lastOrNull(),
            gpsGapActive = gpsGapActive,
            autoPaused = autoPaused,
            routeDeviationMeters = routeDeviationMeters,
            confirmedOffRoute = confirmedOffRoute,
            activityType = activityType,
        )
    }

    private fun publishObservedLocation(point: TrackPoint) {
        _snapshots.value = _snapshots.value.copy(
            latestPoint = point,
            errorMessage = null,
            gpsGapActive = gpsGapActive,
            autoPaused = autoPaused,
            routeDeviationMeters = routeDeviationMeters,
            confirmedOffRoute = confirmedOffRoute,
        )
    }

    private fun publishError(message: String) {
        _snapshots.value = _snapshots.value.copy(errorMessage = message)
    }

    private fun configureRoute(reference: String?) {
        activeRoute = reference?.let {
            runCatching { trackStore.loadStoredTrack(it) }.getOrNull()
        }
        resetRouteDeviationState()
    }

    private fun resetRouteDeviationState() {
        routeDeviationMeters = null
        confirmedOffRoute = false
        routeDeviationMonitor.reset()
    }

    private fun updateRouteDeviation(point: TrackPoint) {
        val route = activeRoute ?: return
        val deviation = GeoMath.distanceToTrackMeters(point, route) ?: return
        routeDeviationMeters = deviation
        val update = routeDeviationMonitor.update(
            deviationMeters = deviation,
            accuracyMeters = point.accuracyMeters,
            nowMillis = point.timeMillis ?: System.currentTimeMillis(),
        )
        confirmedOffRoute = update.confirmedOffRoute
        when (update.event) {
            RouteDeviationEvent.LEFT_ROUTE,
            RouteDeviationEvent.OFF_ROUTE_REMINDER,
            RouteDeviationEvent.RETURNED_TO_ROUTE,
            -> postRouteAlert(update.event, deviation)
            RouteDeviationEvent.NONE -> Unit
        }
    }

    private fun startAsForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stats = _snapshots.value.stats
        val distance = String.format(Locale.GERMANY, "%.2f km", stats.distanceMeters / 1000.0)
        val movingTime = formatNotificationDuration(stats.movingDurationMillis)
        val ascent = stats.ascentMeters.toInt()
        val descent = stats.descentMeters.toInt()
        val title = when {
            _snapshots.value.state == RecordingState.PAUSED -> getString(R.string.notification_title_paused)
            _snapshots.value.autoPaused -> getString(R.string.notification_title_auto_paused)
            else -> getString(R.string.notification_title)
        }
        val stateText = getString(
            R.string.notification_recording_stats,
            movingTime,
            distance,
            ascent,
            descent,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(stateText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(stateText))
            .setContentIntent(launchPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun formatNotificationDuration(millis: Long): String {
        val totalMinutes = millis / 60_000L
        return "%d:%02d h".format(totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val routeAlerts = NotificationChannel(
            ROUTE_ALERT_CHANNEL_ID,
            getString(R.string.route_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.route_alert_channel_description)
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(routeAlerts)
    }

    private fun postRouteAlert(event: RouteDeviationEvent, deviationMeters: Double) {
        val launchIntent = Intent(this, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            2,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val returned = event == RouteDeviationEvent.RETURNED_TO_ROUTE
        val notification = NotificationCompat.Builder(this, ROUTE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                getString(if (returned) R.string.route_alert_returned else R.string.route_alert_off_route),
            )
            .setContentText(
                if (returned) {
                    getString(R.string.route_alert_returned_text)
                } else {
                    getString(R.string.route_alert_off_route_text, deviationMeters.toInt())
                },
            )
            .setContentIntent(launchPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
        getSystemService(NotificationManager::class.java).notify(ROUTE_ALERT_NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "de.wandern.app.action.START"
        const val ACTION_RESTORE = "de.wandern.app.action.RESTORE"
        const val ACTION_PAUSE = "de.wandern.app.action.PAUSE"
        const val ACTION_RESUME = "de.wandern.app.action.RESUME"
        const val ACTION_STOP = "de.wandern.app.action.STOP"
        const val ACTION_DISCARD = "de.wandern.app.action.DISCARD"
        const val EXTRA_ROUTE_NAME = "de.wandern.app.extra.ROUTE_NAME"
        const val EXTRA_ROUTE_REFERENCE = "de.wandern.app.extra.ROUTE_REFERENCE"
        const val EXTRA_ACTIVITY_TYPE = "de.wandern.app.extra.ACTIVITY_TYPE"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 101
        private const val ROUTE_ALERT_CHANNEL_ID = "route_alerts"
        private const val ROUTE_ALERT_NOTIFICATION_ID = 102
        private const val MIN_DISTANCE_METERS = 2.5
        private const val MAX_STATIONARY_INTERVAL_MILLIS = 10_000L
        private const val GPS_GAP_THRESHOLD_MILLIS = 15_000L

        private val _snapshots = MutableStateFlow(TrackingSnapshot())
        val snapshots: StateFlow<TrackingSnapshot> = _snapshots.asStateFlow()
    }
}
