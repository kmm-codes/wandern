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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpsGapInterpolator
import de.wandern.app.model.GpsQuality
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RecordingState
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

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        trackStore = TrackStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_ROUTE_NAME))
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
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
            gpsGapActive = lastAcceptedPoint != null
            publishObservedLocation(point)
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

    private fun startRecording(routeName: String?) {
        if (sessionId != null) return
        sessionId = trackStore.createSession(routeName)
        segmentIndex = 0
        lastAcceptedPoint = null
        lastObservedPoint = null
        gpsGapActive = false
        startAsForeground()
        publishSnapshot(RecordingState.RECORDING)
        requestLocationUpdates()
    }

    private fun pauseRecording() {
        val id = sessionId ?: return
        removeLocationUpdates()
        gpsGapActive = false
        trackStore.updateState(id, RecordingState.PAUSED)
        publishSnapshot(RecordingState.PAUSED)
        updateNotification()
    }

    private fun resumeRecording() {
        val id = sessionId ?: return
        segmentIndex += 1
        lastAcceptedPoint = null
        gpsGapActive = false
        trackStore.updateState(id, RecordingState.RECORDING, segmentIndex)
        publishSnapshot(RecordingState.RECORDING)
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
        )
        sessionId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreAfterProcessRestart() {
        val active = trackStore.activeSession() ?: run {
            stopSelf()
            return
        }
        sessionId = active.id
        segmentIndex = active.segmentIndex
        lastAcceptedPoint = trackStore.loadTrack(active.id).segments.lastOrNull()?.lastOrNull()
        lastObservedPoint = lastAcceptedPoint
        gpsGapActive = false
        startAsForeground()
        publishSnapshot(active.state)
        if (active.state == RecordingState.RECORDING) requestLocationUpdates()
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
        )
    }

    private fun publishObservedLocation(point: TrackPoint) {
        _snapshots.value = _snapshots.value.copy(
            latestPoint = point,
            errorMessage = null,
            gpsGapActive = gpsGapActive,
        )
    }

    private fun publishError(message: String) {
        _snapshots.value = _snapshots.value.copy(errorMessage = message)
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
        val stopIntent = Intent(this, TrackingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stats = _snapshots.value.stats
        val distance = String.format(Locale.GERMANY, "%.2f km", stats.distanceMeters / 1000.0)
        val stateText = if (_snapshots.value.state == RecordingState.PAUSED) "Pausiert · $distance" else distance
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(stateText)
            .setContentIntent(launchPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "de.wandern.app.action.START"
        const val ACTION_PAUSE = "de.wandern.app.action.PAUSE"
        const val ACTION_RESUME = "de.wandern.app.action.RESUME"
        const val ACTION_STOP = "de.wandern.app.action.STOP"
        const val EXTRA_ROUTE_NAME = "de.wandern.app.extra.ROUTE_NAME"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 101
        private const val MIN_DISTANCE_METERS = 2.5
        private const val MAX_STATIONARY_INTERVAL_MILLIS = 10_000L
        private const val GPS_GAP_THRESHOLD_MILLIS = 15_000L

        private val _snapshots = MutableStateFlow(TrackingSnapshot())
        val snapshots: StateFlow<TrackingSnapshot> = _snapshots.asStateFlow()
    }
}
