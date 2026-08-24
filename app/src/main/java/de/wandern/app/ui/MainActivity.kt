package de.wandern.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.R
import de.wandern.app.data.GpxCodec
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.OfflineMapDownloadState
import de.wandern.app.data.OfflineMapDownloader
import de.wandern.app.data.TrackStore
import de.wandern.app.databinding.ActivityMainBinding
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.GpsQuality
import de.wandern.app.model.OfflineMapPlanner
import de.wandern.app.model.RecordingState
import de.wandern.app.model.TrackPoint
import de.wandern.app.model.TrackStats
import de.wandern.app.model.TrackingSnapshot
import de.wandern.app.service.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var trackStore: TrackStore
    private lateinit var offlineMapDownloader: OfflineMapDownloader
    private var map: MapLibreMap? = null
    private var mapStyle: Style? = null
    private var importedTrack: GpxTrack? = null
    private var latestSnapshot = TrackingSnapshot()
    private var pendingRecordingStart = false
    private var pendingCenterRequest = false
    private var followLocation = true
    private var offlineDownloadInProgress = false
    private var latestLocatedPoint: TrackPoint? = null
    private val routeEndpointMarkers = mutableListOf<Marker>()
    private val routeStartIcon: Icon by lazy { createRouteEndpointIcon("S", R.color.forest_700) }
    private val routeEndIcon: Icon by lazy { createRouteEndpointIcon("Z", R.color.warning) }
    private val locationRequestSignals = mutableListOf<CancellationSignal>()
    private val restoreRouteStatusRunnable = Runnable {
        if (!::binding.isInitialized) return@Runnable
        renderLocationStatus(
            latestSnapshot.latestPoint ?: latestLocatedPoint,
            latestSnapshot.gpsGapActive,
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission()
        val startRecording = pendingRecordingStart
        val centerUser = pendingCenterRequest
        pendingRecordingStart = false
        pendingCenterRequest = false
        if (locationGranted && startRecording) {
            sendTrackingAction(TrackingService.ACTION_START, startForeground = true)
        } else if (startRecording) {
            toast("Ohne Standortberechtigung kann keine Tour aufgezeichnet werden.")
        }
        if (locationGranted && centerUser) locateUser()
        else if (centerUser) toast("Ohne Standortberechtigung kann dein Standort nicht angezeigt werden.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        trackStore = TrackStore(this)
        offlineMapDownloader = OfflineMapDownloader(this, MAP_STYLE_URL)
        binding.mapView.onCreate(savedInstanceState)

        setupMap()
        setupActions()
        observeTracking()
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TOUR_REFERENCE)?.let {
            openStoredTour(it)
            intent.removeExtra(EXTRA_TOUR_REFERENCE)
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let(::importGpx)
    }

    private fun setupMap() {
        binding.mapView.getMapAsync { readyMap ->
            map = readyMap.apply {
                uiSettings.isCompassEnabled = true
                uiSettings.isAttributionEnabled = true
                uiSettings.isLogoEnabled = true
                addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) followLocation = false
                }
            }
            readyMap.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                mapStyle = style
                installTrackLayers(style)
                redrawTracks()
            }
        }
    }

    private fun installTrackLayers(style: Style) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineColor(Color.parseColor("#1677FF")),
                lineWidth(6f),
                lineOpacity(0.88f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(LIVE_TRACK_SOURCE, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            LineLayer(LIVE_TRACK_LAYER, LIVE_TRACK_SOURCE).withProperties(
                lineColor(Color.parseColor("#F26B38")),
                lineWidth(5f),
                lineOpacity(0.95f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(INTERPOLATED_TRACK_SOURCE, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            LineLayer(INTERPOLATED_TRACK_LAYER, INTERPOLATED_TRACK_SOURCE).withProperties(
                lineColor(Color.parseColor("#F2A65A")),
                lineWidth(5f),
                lineOpacity(0.95f),
                lineDasharray(arrayOf(1.4f, 1.4f)),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(POSITION_SOURCE, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                circleColor(Color.parseColor("#153A2E")),
                circleRadius(8f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(3f),
            ),
        )
    }

    private fun setupActions() {
        binding.toursButton.setOnClickListener {
            startActivity(Intent(this, TourLibraryActivity::class.java))
        }
        binding.recordButton.setOnClickListener {
            when (latestSnapshot.state) {
                RecordingState.IDLE, RecordingState.FINISHED -> requestRecordingStart()
                RecordingState.RECORDING -> sendTrackingAction(TrackingService.ACTION_PAUSE)
                RecordingState.PAUSED -> sendTrackingAction(TrackingService.ACTION_RESUME)
            }
        }
        binding.moreButton.setOnClickListener { showMoreMenu() }
        binding.centerButton.setOnClickListener { requestCenterOnUser() }
    }

    private fun observeTracking() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                TrackingService.snapshots.collect { snapshot ->
                    latestSnapshot = snapshot
                    renderSnapshot(snapshot)
                }
            }
        }
    }

    private fun renderSnapshot(snapshot: TrackingSnapshot) {
        binding.titleText.text = when (snapshot.state) {
            RecordingState.IDLE -> importedTrack?.name ?: getString(R.string.ready_to_hike)
            RecordingState.RECORDING -> getString(R.string.recording_running)
            RecordingState.PAUSED -> getString(R.string.recording_paused)
            RecordingState.FINISHED -> getString(R.string.recording_saved)
        }
        binding.recordButton.text = when (snapshot.state) {
            RecordingState.IDLE, RecordingState.FINISHED -> getString(R.string.record)
            RecordingState.RECORDING -> getString(R.string.pause)
            RecordingState.PAUSED -> getString(R.string.resume)
        }
        binding.recordButton.setIconResource(
            if (snapshot.state == RecordingState.RECORDING) R.drawable.ic_pause else R.drawable.ic_record,
        )
        renderStats(snapshot.stats)
        snapshot.latestPoint?.let { point ->
            latestLocatedPoint = point
            renderGpsStatus(point)
            if (followLocation && snapshot.state == RecordingState.RECORDING) centerOn(point, 16.5)
        }
        snapshot.errorMessage?.let { toast(it) }
        renderLocationStatus(snapshot.latestPoint ?: latestLocatedPoint, snapshot.gpsGapActive)
        redrawTracks()
    }

    private fun renderStats(stats: TrackStats) {
        binding.distanceText.text = String.format(Locale.GERMANY, "%.2f km", stats.distanceMeters / 1000.0)
        binding.durationText.text = formatDuration(stats.durationMillis)
        binding.paceText.text = stats.paceSecondsPerKilometer?.let(::formatPace) ?: getString(R.string.not_available)
        binding.elevationText.text = getString(
            R.string.elevation_stats,
            stats.ascentMeters.toInt(),
            stats.descentMeters.toInt(),
        )
        binding.slopeText.text = stats.currentSlopePercent?.let {
            getString(R.string.slope_value, it)
        } ?: getString(R.string.slope_empty)
    }

    private fun renderRouteStatus(point: TrackPoint?) {
        if (offlineDownloadInProgress) return
        val route = importedTrack
        if (point == null || route == null) {
            hideRouteStatus()
            return
        }
        val deviation = GeoMath.distanceToTrackMeters(point, route) ?: return
        if (deviation <= OFF_ROUTE_THRESHOLD_METERS) {
            showRouteStatus(getString(R.string.on_route, deviation.toInt()), R.color.forest_900)
        } else {
            showRouteStatus(getString(R.string.off_route, deviation.toInt()), R.color.warning)
        }
    }

    private fun renderLocationStatus(point: TrackPoint?, gpsGapActive: Boolean) {
        if (offlineDownloadInProgress) return
        val accuracy = point?.accuracyMeters
        when {
            gpsGapActive -> showRouteStatus(getString(R.string.gps_gap_recording), R.color.warning)
            point != null && locationAgeMinutes(point) >= STALE_LOCATION_MINUTES -> {
                showRouteStatus(
                    getString(R.string.gps_position_stale, locationAgeMinutes(point)),
                    R.color.warning,
                )
            }
            accuracy != null && accuracy > GpsQuality.RELIABLE_ACCURACY_METERS -> {
                showRouteStatus(
                    getString(R.string.gps_position_uncertain, accuracy.toInt()),
                    R.color.warning,
                )
            }
            else -> renderRouteStatus(point)
        }
    }

    private fun renderGpsStatus(point: TrackPoint) {
        val accuracy = point.accuracyMeters
        val ageMinutes = locationAgeMinutes(point)
        binding.gpsText.text = when {
            ageMinutes >= STALE_LOCATION_MINUTES -> {
                getString(R.string.gps_last_known, ageMinutes, accuracy?.toInt() ?: 0)
            }
            accuracy == null -> getString(R.string.gps_active)
            accuracy > GpsQuality.RELIABLE_ACCURACY_METERS -> {
                getString(R.string.gps_inaccurate, accuracy.toInt())
            }
            else -> getString(R.string.gps_accuracy, accuracy.toInt())
        }
        binding.gpsText.setTextColor(
            ContextCompat.getColor(
                this,
                if (ageMinutes >= STALE_LOCATION_MINUTES ||
                    accuracy != null && accuracy > GpsQuality.RELIABLE_ACCURACY_METERS
                ) {
                    R.color.warning
                } else {
                    R.color.sand_50
                },
            ),
        )
    }

    private fun locationAgeMinutes(point: TrackPoint): Int {
        val timestamp = point.timeMillis ?: return 0
        return ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 60_000L)
            .toInt()
    }

    private fun showRouteStatus(message: CharSequence, colorRes: Int, autoHideMillis: Long? = null) {
        binding.routeStatusText.removeCallbacks(restoreRouteStatusRunnable)
        binding.routeStatusText.text = message
        binding.routeStatusText.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.routeStatusText.visibility = View.VISIBLE
        autoHideMillis?.let { binding.routeStatusText.postDelayed(restoreRouteStatusRunnable, it) }
    }

    private fun hideRouteStatus() {
        binding.routeStatusText.removeCallbacks(restoreRouteStatusRunnable)
        binding.routeStatusText.visibility = View.GONE
    }

    private fun importGpx(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val parsedTrack = contentResolver.openInputStream(uri)?.use {
                        GpxCodec.parse(it, uri.lastPathSegment ?: "Importierte Route")
                    } ?: error("Datei konnte nicht geöffnet werden.")
                    val track = runCatching { ElevationEnricher().enrichIfMissing(parsedTrack) }
                        .onFailure { Log.w(LOG_TAG, "Elevation enrichment failed", it) }
                        .getOrDefault(parsedTrack)
                    val stored = trackStore.saveImportedTrack(track)
                    track to stored.reference
                }
            }
            result.onSuccess { (track, reference) ->
                displayTrack(track, askForOfflineDownload = false)
                askToDownloadOfflineMap(track) { openTourDetails(reference) }
            }.onFailure {
                Log.e(LOG_TAG, "GPX import failed for $uri", it)
                toast("GPX konnte nicht geladen werden: ${it.localizedMessage}")
            }
        }
    }

    private fun openStoredTour(reference: String) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { trackStore.loadStoredTrack(reference) }
            }
            result.onSuccess { displayTrack(it, askForOfflineDownload = false) }
                .onFailure {
                    toast(getString(R.string.tour_open_error, it.localizedMessage ?: "Unbekannter Fehler"))
                }
        }
    }

    private fun displayTrack(track: GpxTrack, askForOfflineDownload: Boolean) {
        importedTrack = track
        binding.titleText.text = track.name
        showRouteStatus(
            getString(R.string.route_points_loaded, track.points.size),
            R.color.forest_900,
            ROUTE_LOADED_BADGE_MILLIS,
        )
        redrawTracks()
        fitTrack(track)
        if (askForOfflineDownload) askToDownloadOfflineMap(track)
    }

    private fun askToDownloadOfflineMap(track: GpxTrack, onDecision: () -> Unit = {}) {
        val plan = runCatching { OfflineMapPlanner.plan(track) }.getOrElse {
            showRouteStatus(
                getString(R.string.offline_map_error, it.localizedMessage ?: "Unbekannter Fehler"),
                R.color.warning,
                ERROR_BADGE_MILLIS,
            )
            onDecision()
            return
        }
        var answered = false
        fun continueOnline() {
            if (answered) return
            answered = true
            showRouteStatus(
                getString(R.string.offline_map_skipped),
                R.color.forest_900,
                INFO_BADGE_MILLIS,
            )
            onDecision()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_map_question_title)
            .setMessage(
                getString(
                    R.string.offline_map_question_message,
                    plan.maxZoom,
                    plan.estimatedTileCount,
                ),
            )
            .setNegativeButton(R.string.offline_map_online_only) { _, _ -> continueOnline() }
            .setPositiveButton(R.string.offline_map_download) { _, _ ->
                answered = true
                downloadOfflineMap(track)
                onDecision()
            }
            .setOnCancelListener { continueOnline() }
            .show()
    }

    private fun openTourDetails(reference: String) {
        startActivity(
            Intent(this, TourDetailActivity::class.java)
                .putExtra(TourDetailActivity.EXTRA_TOUR_REFERENCE, reference),
        )
    }

    private fun downloadOfflineMap(track: GpxTrack) {
        offlineMapDownloader.download(track) { state ->
            when (state) {
                is OfflineMapDownloadState.Planning -> {
                    offlineDownloadInProgress = true
                    showRouteStatus(
                        getString(R.string.offline_map_planning, state.plan.maxZoom),
                        R.color.forest_900,
                    )
                }
                is OfflineMapDownloadState.Progress -> {
                    offlineDownloadInProgress = true
                    val size = Formatter.formatShortFileSize(this, state.downloadedBytes)
                    val message = state.percent?.let {
                        getString(R.string.offline_map_progress_percent, it, size)
                    } ?: getString(R.string.offline_map_progress, size)
                    showRouteStatus(message, R.color.forest_900)
                }
                is OfflineMapDownloadState.Complete -> {
                    offlineDownloadInProgress = false
                    val size = Formatter.formatShortFileSize(this, state.downloadedBytes)
                    showRouteStatus(
                        getString(R.string.offline_map_complete, size),
                        R.color.forest_900,
                        SUCCESS_BADGE_MILLIS,
                    )
                }
                is OfflineMapDownloadState.Error -> {
                    offlineDownloadInProgress = false
                    showRouteStatus(
                        getString(R.string.offline_map_error, state.message),
                        R.color.warning,
                        ERROR_BADGE_MILLIS,
                    )
                }
            }
        }
    }

    private fun redrawTracks() {
        val style = mapStyle ?: return
        updateLineSource(style, ROUTE_SOURCE, importedTrack)
        updateRouteEndpointMarkers(importedTrack)
        updateLiveTrackSources(style, latestSnapshot.track)
        val pointSource = style.getSourceAs<GeoJsonSource>(POSITION_SOURCE) ?: return
        val point = latestSnapshot.latestPoint ?: latestLocatedPoint
        if (point == null) {
            pointSource.setGeoJson(EMPTY_FEATURE_COLLECTION)
        } else {
            pointSource.setGeoJson(
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)),
                ),
            )
        }
        style.getLayerAs<CircleLayer>(POSITION_LAYER)?.setProperties(
            circleColor(
                if ((point?.accuracyMeters ?: 0f) > GpsQuality.RELIABLE_ACCURACY_METERS) {
                    Color.parseColor("#F26B38")
                } else {
                    Color.parseColor("#153A2E")
                },
            ),
        )
    }

    private fun updateRouteEndpointMarkers(track: GpxTrack?) {
        val readyMap = map ?: return
        routeEndpointMarkers.forEach(readyMap::removeMarker)
        routeEndpointMarkers.clear()
        val points = track?.points.orEmpty()
        if (points.isEmpty()) return

        val start = points.first()
        val end = points.last()
        // A separate finish marker would cover the start on circular routes.
        if (GeoMath.distanceMeters(start, end) >= CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS) {
            routeEndpointMarkers += readyMap.addMarker(
                MarkerOptions()
                    .position(LatLng(end.latitude, end.longitude))
                    .icon(routeEndIcon)
                    .title(getString(R.string.route_end)),
            )
        }
        routeEndpointMarkers += readyMap.addMarker(
            MarkerOptions()
                .position(LatLng(start.latitude, start.longitude))
                .icon(routeStartIcon)
                .title(getString(R.string.route_start)),
        )
    }

    private fun createRouteEndpointIcon(label: String, colorRes: Int): Icon {
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val strokeWidth = 2.5f * density
        val radius = center - strokeWidth
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawCircle(center, center, radius, paint)
        paint.color = ContextCompat.getColor(this, colorRes)
        canvas.drawCircle(center, center, radius - strokeWidth, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 16 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        val baseline = center - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, center, baseline, paint)
        return IconFactory.getInstance(this).fromBitmap(bitmap)
    }

    private fun updateLiveTrackSources(style: Style, track: GpxTrack) {
        val solidFeatures = mutableListOf<Feature>()
        val interpolatedFeatures = mutableListOf<Feature>()
        track.segments.forEach { segment ->
            segment.zipWithNext().forEach { (start, end) ->
                val feature = Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(start.longitude, start.latitude),
                            Point.fromLngLat(end.longitude, end.latitude),
                        ),
                    ),
                )
                if (start.isInterpolated || end.isInterpolated) {
                    interpolatedFeatures += feature
                } else {
                    solidFeatures += feature
                }
            }
        }
        style.getSourceAs<GeoJsonSource>(LIVE_TRACK_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(solidFeatures),
        )
        style.getSourceAs<GeoJsonSource>(INTERPOLATED_TRACK_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(interpolatedFeatures),
        )
    }

    private fun updateLineSource(style: Style, sourceId: String, track: GpxTrack?) {
        val source = style.getSourceAs<GeoJsonSource>(sourceId) ?: return
        val features = track?.segments.orEmpty().filter { it.size >= 2 }.map { segment ->
            Feature.fromGeometry(
                LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun requestRecordingStart() {
        if (hasLocationPermission()) {
            sendTrackingAction(TrackingService.ACTION_START, startForeground = true)
            return
        }
        pendingRecordingStart = true
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun sendTrackingAction(action: String, startForeground: Boolean = false) {
        val intent = Intent(this, TrackingService::class.java)
            .setAction(action)
            .apply {
                if (action == TrackingService.ACTION_START) {
                    importedTrack?.name?.let { putExtra(TrackingService.EXTRA_ROUTE_NAME, it) }
                }
            }
        if (startForeground) ContextCompat.startForegroundService(this, intent) else startService(intent)
    }

    private fun showMoreMenu() {
        PopupMenu(this, binding.moreButton).apply {
            if (latestSnapshot.state == RecordingState.RECORDING || latestSnapshot.state == RecordingState.PAUSED) {
                menu.add(0, MENU_STOP, 0, "Beenden & speichern")
            }
            if (importedTrack != null) {
                menu.add(0, MENU_FIT_ROUTE, 2, getString(R.string.fit_route))
                menu.add(0, MENU_CLEAR_ROUTE, 3, "Route ausblenden")
            }
            if (menu.size() == 0) menu.add("Keine weiteren Aktionen").isEnabled = false
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_STOP -> {
                        sendTrackingAction(TrackingService.ACTION_STOP)
                        true
                    }
                    MENU_CLEAR_ROUTE -> {
                        importedTrack = null
                        hideRouteStatus()
                        redrawTracks()
                        true
                    }
                    MENU_FIT_ROUTE -> {
                        importedTrack?.let(::fitTrack)
                        followLocation = false
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun fitTrack(track: GpxTrack) {
        val points = track.points
        val readyMap = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1) {
            centerOn(points.first(), 15.0)
            return
        }
        val bounds = LatLngBounds.Builder().also { builder ->
            points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        }.build()
        binding.mapView.post {
            val density = resources.displayMetrics.density
            val horizontalPadding = (24 * density).toInt()
            val topPadding = (
                binding.headerCard.bottom - binding.mapView.top + (16 * density).toInt()
            ).coerceAtLeast(horizontalPadding)
            val bottomPadding = (
                binding.mapView.bottom - binding.actionsCard.top + (16 * density).toInt()
            ).coerceAtLeast(horizontalPadding)
            readyMap.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds,
                    horizontalPadding,
                    topPadding,
                    horizontalPadding,
                    bottomPadding,
                ),
                700,
            )
        }
    }

    private fun centerOn(point: TrackPoint, zoom: Double) {
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), zoom),
            500,
        )
    }

    private fun requestCenterOnUser() {
        followLocation = true
        (latestSnapshot.latestPoint ?: latestLocatedPoint)?.let { centerOn(it, 16.0) }
        if (hasLocationPermission()) {
            locateUser()
        } else {
            pendingCenterRequest = true
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun locateUser() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            toast("Standortdienste sind deaktiviert.")
            return
        }

        val lastKnown = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (lastKnown != null) showLocatedPosition(lastKnown)
        else {
            binding.gpsText.setText(R.string.gps_locating)
            showRouteStatus(getString(R.string.gps_locating), R.color.forest_900)
        }

        locationRequestSignals.forEach(CancellationSignal::cancel)
        locationRequestSignals.clear()
        providers.forEach { provider ->
            val signal = CancellationSignal()
            locationRequestSignals += signal
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                ContextCompat.getMainExecutor(this),
            ) { location ->
                if (location != null) {
                    showLocatedPosition(location)
                } else if (latestLocatedPoint == null && latestSnapshot.latestPoint == null) {
                    binding.gpsText.setText(R.string.gps_no_fix)
                    showRouteStatus(getString(R.string.gps_no_fix), R.color.warning)
                }
            }
        }
    }

    private fun showLocatedPosition(location: Location) {
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            elevationMeters = location.altitude.takeIf { location.hasAltitude() },
            timeMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
        )
        val existing = latestSnapshot.latestPoint ?: latestLocatedPoint
        if (existing != null && !isBetterLocation(point, existing)) return
        latestLocatedPoint = point
        renderGpsStatus(point)
        renderLocationStatus(point, latestSnapshot.gpsGapActive)
        redrawTracks()
        centerOn(point, 16.0)
    }

    private fun isBetterLocation(candidate: TrackPoint, current: TrackPoint): Boolean {
        val timeDelta = (candidate.timeMillis ?: 0L) - (current.timeMillis ?: 0L)
        if (timeDelta > SIGNIFICANT_LOCATION_TIME_MILLIS) return true
        if (timeDelta < -SIGNIFICANT_LOCATION_TIME_MILLIS) return false
        val candidateAccuracy = candidate.accuracyMeters ?: Float.MAX_VALUE
        val currentAccuracy = current.accuracyMeters ?: Float.MAX_VALUE
        return candidateAccuracy <= currentAccuracy || (timeDelta > 0 && candidateAccuracy <= currentAccuracy * 1.5f)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun formatPace(secondsPerKilometer: Double): String {
        val seconds = secondsPerKilometer.toInt().coerceAtMost(99 * 60 + 59)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { binding.mapView.onPause(); super.onPause() }
    override fun onStop() { binding.mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() {
        binding.routeStatusText.removeCallbacks(restoreRouteStatusRunnable)
        locationRequestSignals.forEach(CancellationSignal::cancel)
        locationRequestSignals.clear()
        binding.mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapView.onSaveInstanceState(outState) }

    companion object {
        const val EXTRA_TOUR_REFERENCE = "de.wandern.app.MAIN_TOUR_REFERENCE"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val LOG_TAG = "WandernImport"
        private const val ROUTE_SOURCE = "imported-route-source"
        private const val ROUTE_LAYER = "imported-route-layer"
        private const val LIVE_TRACK_SOURCE = "live-track-source"
        private const val LIVE_TRACK_LAYER = "live-track-layer"
        private const val INTERPOLATED_TRACK_SOURCE = "interpolated-track-source"
        private const val INTERPOLATED_TRACK_LAYER = "interpolated-track-layer"
        private const val POSITION_SOURCE = "position-source"
        private const val POSITION_LAYER = "position-layer"
        private const val OFF_ROUTE_THRESHOLD_METERS = 50.0
        private const val STALE_LOCATION_MINUTES = 2
        private const val SIGNIFICANT_LOCATION_TIME_MILLIS = 120_000L
        private const val CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS = 50.0
        private const val ROUTE_LOADED_BADGE_MILLIS = 3_000L
        private const val INFO_BADGE_MILLIS = 4_000L
        private const val SUCCESS_BADGE_MILLIS = 4_000L
        private const val ERROR_BADGE_MILLIS = 8_000L
        private const val MENU_STOP = 1
        private const val MENU_CLEAR_ROUTE = 3
        private const val MENU_FIT_ROUTE = 5
        private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
