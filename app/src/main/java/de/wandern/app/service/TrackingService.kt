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
import de.wandern.app.data.DetourSessionStore
import de.wandern.app.data.RecordingRouteStore
import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.AutoPauseDetector
import de.wandern.app.model.AutoPauseTransition
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpsGapAction
import de.wandern.app.model.GpsGapInterpolator
import de.wandern.app.model.GpsGapPolicy
import de.wandern.app.model.GpsQualityWarningMonitor
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.LocationSample
import de.wandern.app.model.LocationSampleDecision
import de.wandern.app.model.LocationSamplePipeline
import de.wandern.app.model.LocationSampleSource
import de.wandern.app.model.RecordingState
import de.wandern.app.model.RecordingClock
import de.wandern.app.model.RouteDeviationEvent
import de.wandern.app.model.RouteDeviationMonitor
import de.wandern.app.model.TrackAnalyzer
import de.wandern.app.model.TrackPoint
import de.wandern.app.model.TrackStats
import de.wandern.app.model.TrackingSnapshot
import de.wandern.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var trackStore: TrackStore
    private lateinit var detourStore: DetourSessionStore
    private lateinit var recordingRouteStore: RecordingRouteStore
    private var sessionId: Long? = null
    private var segmentIndex = 0
    private var lastAcceptedPoint: TrackPoint? = null
    private var lastAcceptedElapsedRealtimeMillis: Long? = null
    private var lastTrustedPoint: TrackPoint? = null
    private var lastObservedPoint: TrackPoint? = null
    private var gpsGapActive = false
    private val gpsQualityWarningMonitor = GpsQualityWarningMonitor()
    private var autoPaused = false
    private val autoPauseDetector = AutoPauseDetector()
    private var activityType = ActivityType.HIKING
    private var locationPipeline = LocationSamplePipeline(activityType)
    private var activeRoute: GpxTrack? = null
    private var routeDeviationMeters: Double? = null
    private var confirmedOffRoute = false
    private val routeDeviationMonitor = RouteDeviationMonitor()
    private val recordingClock = RecordingClock()
    private var lastFullSnapshotElapsedRealtime = 0L
    private var lastNotificationElapsedRealtime = 0L
    private var bootEpochOffsetMillis = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        trackStore = TrackStore(this)
        detourStore = DetourSessionStore(this)
        recordingRouteStore = RecordingRouteStore(this)
        bootEpochOffsetMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()
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
            ACTION_UPDATE_NAVIGATION_ROUTE -> updateNavigationRoute()
            else -> restoreAfterProcessRestart()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        if (_snapshots.value.state != RecordingState.RECORDING) return
        val elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000L
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            elevationMeters = location.altitude.takeIf { location.hasAltitude() },
            timeMillis = bootEpochOffsetMillis + elapsedRealtimeMillis,
            accuracyMeters = location.accuracy,
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
        )
        lastObservedPoint = point
        val decision = locationPipeline.process(
            LocationSample(
                point = point,
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                source = location.sampleSource(),
            ),
        )
        handleLocationDecision(decision, point)
    }

    private fun handleLocationDecision(decision: LocationSampleDecision, observedPoint: TrackPoint) {
        val warningConfirmed = when {
            decision.trustedSamples.isNotEmpty() -> gpsQualityWarningMonitor.update(
                isPoor = false,
                sampleMillis = observedPoint.timeMillis ?: System.currentTimeMillis(),
            )
            decision.marksGpsGap -> gpsQualityWarningMonitor.update(
                isPoor = true,
                sampleMillis = observedPoint.timeMillis ?: System.currentTimeMillis(),
            )
            else -> gpsGapActive
        }
        if (decision.trustedSamples.isEmpty()) {
            if (decision.marksGpsGap) {
                gpsGapActive = !autoPaused && lastTrustedPoint != null && warningConfirmed
            }
            publishObservedLocation(observedPoint)
            return
        }
        if (decision.startNewSegment) startNewTrackSegment()
        decision.trustedSamples.forEach(::processTrustedSample)
    }

    private fun processTrustedSample(sample: LocationSample) {
        val point = sample.point
        lastTrustedPoint = point
        updateRouteDeviation(point)

        val autoPauseUpdate = autoPauseDetector.update(point, sample.elapsedRealtimeMillis)
        autoPaused = autoPauseUpdate.autoPaused
        when {
            autoPauseUpdate.stationaryEvidence -> recordingClock.setMoving(false, sample.elapsedRealtimeMillis)
            autoPauseUpdate.movingEvidence -> recordingClock.setMoving(true, sample.elapsedRealtimeMillis)
        }
        if (autoPauseUpdate.transition == AutoPauseTransition.RESUMED) {
            // A stationary interval is not a GPS gap and must never be interpolated.
            lastAcceptedPoint = null
            lastAcceptedElapsedRealtimeMillis = null
            startNewTrackSegment()
        }
        if ((autoPaused || autoPauseUpdate.stationaryEvidence) && lastAcceptedPoint != null) {
            gpsGapActive = false
            publishTrustedLocation(point)
            if (autoPauseUpdate.transition != AutoPauseTransition.NONE) updateNotification()
            return
        }

        var previous = lastAcceptedPoint
        val previousElapsedRealtime = lastAcceptedElapsedRealtimeMillis
        val gapAction = if (previous != null && previousElapsedRealtime != null) {
            GpsGapPolicy.decide(
                previous = previous,
                current = point,
                elapsedMillis = sample.elapsedRealtimeMillis - previousElapsedRealtime,
                activityType = activityType,
                interpolationThresholdMillis = GPS_GAP_THRESHOLD_MILLIS,
                maximumInterpolationMillis = MAX_INTERPOLATED_GAP_MILLIS,
            )
        } else {
            GpsGapAction.NONE
        }
        if (gapAction == GpsGapAction.START_NEW_SEGMENT) {
            startNewTrackSegment()
            previous = null
        }
        if (previous != null) {
            val distance = GeoMath.distanceMeters(previous, point)
            val elapsed = previousElapsedRealtime?.let { sample.elapsedRealtimeMillis - it } ?: Long.MAX_VALUE
            if (distance < MIN_DISTANCE_METERS && elapsed < MAX_STATIONARY_INTERVAL_MILLIS) {
                gpsGapActive = false
                publishTrustedLocation(point)
                return
            }
        }

        val activeSessionId = sessionId ?: return
        runCatching {
            val interpolated = previous?.let {
                if (gapAction == GpsGapAction.INTERPOLATE) {
                    GpsGapInterpolator.between(it, point)
                } else {
                    emptyList()
                }
            }.orEmpty()
            trackStore.appendPoints(activeSessionId, segmentIndex, interpolated + point)
            lastAcceptedPoint = point
            lastAcceptedElapsedRealtimeMillis = sample.elapsedRealtimeMillis
            gpsGapActive = false
            publishRecordingUpdate(point)
            updateNotificationIfDue()
        }.onFailure {
            publishError(getString(R.string.point_save_error, it.localizedMessage ?: getString(R.string.unknown_error)))
        }
    }

    private fun startNewTrackSegment() {
        segmentIndex += 1
        lastAcceptedPoint = null
        lastAcceptedElapsedRealtimeMillis = null
        sessionId?.let { trackStore.updateState(it, RecordingState.RECORDING, segmentIndex) }
    }

    private fun Location.sampleSource(): LocationSampleSource = when (provider) {
        LocationManager.GPS_PROVIDER -> LocationSampleSource.GPS
        LocationManager.NETWORK_PROVIDER -> LocationSampleSource.NETWORK
        else -> LocationSampleSource.OTHER
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        publishError(getString(R.string.gps_disabled))
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
        locationPipeline.setActivityType(activityType)
        locationPipeline.reset()
        sessionId = trackStore.createSession(routeName, routeReference, activityType)
        recordingClock.start(SystemClock.elapsedRealtime())
        segmentIndex = 0
        lastAcceptedPoint = null
        lastAcceptedElapsedRealtimeMillis = null
        lastTrustedPoint = null
        lastObservedPoint = null
        gpsGapActive = false
        gpsQualityWarningMonitor.reset()
        autoPaused = false
        autoPauseDetector.reset()
        configureRoute(routeReference)
        startAsForeground()
        publishSnapshot(RecordingState.RECORDING)
        requestLocationUpdates()
    }

    private fun pauseRecording() {
        val id = sessionId ?: return
        recordingClock.setMoving(false, SystemClock.elapsedRealtime())
        removeLocationUpdates()
        gpsGapActive = false
        gpsQualityWarningMonitor.reset()
        autoPaused = false
        autoPauseDetector.reset()
        trackStore.updateState(id, RecordingState.PAUSED)
        publishSnapshot(RecordingState.PAUSED)
        updateNotification()
    }

    private fun resumeRecording() {
        if (sessionId == null) return
        // Movement time resumes with the first reliable movement evidence, not merely the tap.
        recordingClock.setMoving(false, SystemClock.elapsedRealtime())
        startNewTrackSegment()
        locationPipeline.reset()
        gpsGapActive = false
        gpsQualityWarningMonitor.reset()
        autoPaused = false
        autoPauseDetector.reset()
        resetRouteDeviationState()
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
        val stoppedAtElapsedRealtime = SystemClock.elapsedRealtime()
        recordingClock.setMoving(false, stoppedAtElapsedRealtime)
        val file = runCatching { trackStore.finishSession(id) }.getOrElse {
            publishError(getString(R.string.finish_tour_error, it.localizedMessage ?: getString(R.string.unknown_error)))
            return
        }
        val track = trackStore.loadTrack(id)
        detourStore.clear(id)
        recordingRouteStore.clear(id)
        _snapshots.value = TrackingSnapshot(
            state = RecordingState.FINISHED,
            track = track,
            stats = withRecordingTimes(TrackAnalyzer.calculate(track), stoppedAtElapsedRealtime),
            latestPoint = lastTrustedPoint ?: track.points.lastOrNull(),
            latestObservedPoint = lastObservedPoint,
            savedTrackPath = file.absolutePath,
            activityType = activityType,
            capturedAtElapsedRealtimeMillis = stoppedAtElapsedRealtime,
        )
        sessionId = null
        activityType = ActivityType.HIKING
        locationPipeline.setActivityType(activityType)
        locationPipeline.reset()
        recordingClock.reset()
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
            publishError(getString(R.string.discard_recording_error))
            return
        }
        detourStore.clear(id)
        recordingRouteStore.clear(id)
        sessionId = null
        lastAcceptedPoint = null
        lastAcceptedElapsedRealtimeMillis = null
        lastTrustedPoint = null
        lastObservedPoint = null
        gpsGapActive = false
        gpsQualityWarningMonitor.reset()
        autoPaused = false
        autoPauseDetector.reset()
        locationPipeline.reset()
        recordingClock.reset()
        _snapshots.value = TrackingSnapshot()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreAfterProcessRestart() {
        // Activity recreation must not reset an already running filter or register the listener
        // again. A new service instance has no session id and restores from persistent storage.
        if (sessionId != null) return
        val active = trackStore.activeSession() ?: run {
            stopSelf()
            return
        }
        restoreActiveSession(active)
    }

    private fun restoreActiveSession(active: TrackStore.SessionInfo) {
        sessionId = active.id
        activityType = active.activityType
        locationPipeline.setActivityType(activityType)
        segmentIndex = active.segmentIndex
        val restoredTrack = trackStore.loadTrack(active.id)
        val restoredStats = TrackAnalyzer.calculate(restoredTrack)
        lastAcceptedPoint = restoredTrack.segments.lastOrNull()?.lastOrNull()
        lastAcceptedElapsedRealtimeMillis = null
        lastTrustedPoint = lastAcceptedPoint
        lastObservedPoint = lastAcceptedPoint
        gpsGapActive = false
        gpsQualityWarningMonitor.reset()
        autoPaused = false
        autoPauseDetector.reset()
        recordingClock.start(
            nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            totalMillis = (System.currentTimeMillis() - active.startedAtMillis).coerceAtLeast(0L),
            movingMillis = restoredStats.movingDurationMillis,
            moving = false,
        )
        locationPipeline.reset(lastAcceptedPoint)
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
            publishError(getString(R.string.location_permission_missing))
            return
        }
        runCatching {
            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // Auto-pause needs periodic evidence while the device is stationary. A positive
                // minimum distance can starve the detector of fixes precisely when it must pause.
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 0f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8_000L, 0f, this)
            }
        }.onFailure {
            publishError(getString(R.string.location_start_error, it.localizedMessage ?: getString(R.string.unknown_error)))
        }
    }

    private fun removeLocationUpdates() {
        runCatching { locationManager.removeUpdates(this) }
    }

    private fun publishSnapshot(state: RecordingState) {
        val id = sessionId ?: return
        val track = trackStore.loadTrack(id)
        val capturedAt = SystemClock.elapsedRealtime()
        _snapshots.value = TrackingSnapshot(
            state = state,
            track = track,
            stats = withRecordingTimes(TrackAnalyzer.calculate(track), capturedAt),
            latestPoint = lastTrustedPoint ?: track.points.lastOrNull(),
            latestObservedPoint = lastObservedPoint,
            gpsGapActive = gpsGapActive,
            autoPaused = autoPaused,
            routeDeviationMeters = routeDeviationMeters,
            confirmedOffRoute = confirmedOffRoute,
            activityType = activityType,
            capturedAtElapsedRealtimeMillis = capturedAt,
            movementTimeRunning = recordingClock.isMoving,
        )
        lastFullSnapshotElapsedRealtime = capturedAt
    }

    private fun publishRecordingUpdate(point: TrackPoint) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFullSnapshotElapsedRealtime >= FULL_SNAPSHOT_INTERVAL_MILLIS) {
            publishSnapshot(RecordingState.RECORDING)
        } else {
            publishTrustedLocation(point)
        }
    }

    private fun publishObservedLocation(point: TrackPoint) {
        val capturedAt = SystemClock.elapsedRealtime()
        _snapshots.value = _snapshots.value.copy(
            stats = withRecordingTimes(_snapshots.value.stats, capturedAt),
            latestObservedPoint = point,
            errorMessage = null,
            gpsGapActive = gpsGapActive,
            autoPaused = autoPaused,
            routeDeviationMeters = routeDeviationMeters,
            confirmedOffRoute = confirmedOffRoute,
            capturedAtElapsedRealtimeMillis = capturedAt,
            movementTimeRunning = recordingClock.isMoving,
        )
    }

    private fun publishTrustedLocation(point: TrackPoint) {
        val capturedAt = SystemClock.elapsedRealtime()
        _snapshots.value = _snapshots.value.copy(
            stats = withRecordingTimes(_snapshots.value.stats, capturedAt),
            latestPoint = point,
            latestObservedPoint = lastObservedPoint ?: point,
            errorMessage = null,
            gpsGapActive = gpsGapActive,
            autoPaused = autoPaused,
            routeDeviationMeters = routeDeviationMeters,
            confirmedOffRoute = confirmedOffRoute,
            capturedAtElapsedRealtimeMillis = capturedAt,
            movementTimeRunning = recordingClock.isMoving,
        )
    }

    private fun withRecordingTimes(stats: TrackStats, capturedAt: Long): TrackStats {
        val durations = recordingClock.snapshot(capturedAt)
        val averageSpeed = if (durations.movingMillis > 0L) {
            stats.distanceMeters / (durations.movingMillis / 1000.0)
        } else {
            0.0
        }
        return stats.copy(
            durationMillis = durations.totalMillis,
            movingDurationMillis = durations.movingMillis,
            pauseDurationMillis = durations.pauseMillis,
            averageSpeedMetersPerSecond = averageSpeed,
            paceSecondsPerKilometer = averageSpeed.takeIf { it > 0.0 }?.let { 1000.0 / it },
        )
    }

    private fun publishError(message: String) {
        _snapshots.value = _snapshots.value.copy(errorMessage = message)
    }

    private fun configureRoute(reference: String?) {
        activeRoute = sessionId?.let { id ->
            recordingRouteStore.load(id)?.route ?: detourStore.load(id)?.route
        } ?: reference?.let { runCatching { trackStore.loadStoredTrack(it) }.getOrNull() }
        resetRouteDeviationState()
    }

    private fun updateNavigationRoute() {
        val active = trackStore.activeSession() ?: return
        if (sessionId == null) restoreActiveSession(active)
        configureRoute(active.routeReference)
        publishSnapshot(active.state)
        updateNotification()
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
        lastNotificationElapsedRealtime = SystemClock.elapsedRealtime()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        lastNotificationElapsedRealtime = SystemClock.elapsedRealtime()
    }

    private fun updateNotificationIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationElapsedRealtime >= NOTIFICATION_UPDATE_INTERVAL_MILLIS) {
            updateNotification()
        }
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
        val distance = String.format(AppLanguage.forContext(this).locale, "%.2f km", stats.distanceMeters / 1000.0)
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
        ).apply {
            description = getString(R.string.notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
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
        const val ACTION_UPDATE_NAVIGATION_ROUTE = "de.wandern.app.action.UPDATE_NAVIGATION_ROUTE"
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
        private const val MAX_INTERPOLATED_GAP_MILLIS = 90_000L
        private const val FULL_SNAPSHOT_INTERVAL_MILLIS = 15_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 30_000L

        private val _snapshots = MutableStateFlow(TrackingSnapshot())
        val snapshots: StateFlow<TrackingSnapshot> = _snapshots.asStateFlow()
    }
}
