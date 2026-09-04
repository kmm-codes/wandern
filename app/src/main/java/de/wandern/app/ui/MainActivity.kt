package de.wandern.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.IntentFilter
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import de.wandern.app.BuildConfig
import de.wandern.app.R
import de.wandern.app.data.GpxCodec
import de.wandern.app.data.ActivityPreferences
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.DetourSessionStore
import de.wandern.app.data.FitnessPreferences
import de.wandern.app.data.NavigationPreferences
import de.wandern.app.data.OfflineMapAvailability
import de.wandern.app.data.OfflineMapDownloadState
import de.wandern.app.data.OfflineMapDownloader
import de.wandern.app.data.OfflineMapStatus
import de.wandern.app.data.RecordingRouteStore
import de.wandern.app.data.TrackStore
import de.wandern.app.localization.AppLanguage
import de.wandern.app.databinding.ActivityMainBinding
import de.wandern.app.databinding.DialogRecordingStartCheckBinding
import de.wandern.app.model.GeoMath
import de.wandern.app.model.ActivityType
import de.wandern.app.model.DetourPlanner
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.GpsQuality
import de.wandern.app.model.GpsQualityWarningMonitor
import de.wandern.app.model.HeadingSmoother
import de.wandern.app.model.HikingFitnessLevel
import de.wandern.app.model.NavigationGuidance
import de.wandern.app.model.NavigationGuidanceTracker
import de.wandern.app.model.NavigationManeuver
import de.wandern.app.model.NavigationManeuverType
import de.wandern.app.model.OfflineMapPlanner
import de.wandern.app.model.RecordingState
import de.wandern.app.model.RecordingRetentionPolicy
import de.wandern.app.model.RouteProgress
import de.wandern.app.model.RouteProgressTracker
import de.wandern.app.model.RouteRejoinAdvisor
import de.wandern.app.model.RouteAdjustmentKind
import de.wandern.app.model.RouteEntryMode
import de.wandern.app.model.RouteStartAssessment
import de.wandern.app.model.RouteStartAssessor
import de.wandern.app.model.RouteStartSituation
import de.wandern.app.model.SpeedSmoother
import de.wandern.app.model.TrackPoint
import de.wandern.app.model.TrackStats
import de.wandern.app.model.TourForecaster
import de.wandern.app.model.TourInsightsAnalyzer
import de.wandern.app.model.TrackingSnapshot
import de.wandern.app.model.projectRecordingDurations
import de.wandern.app.service.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.ShoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconKeepUpright
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private lateinit var binding: ActivityMainBinding
    private val displayLocale get() = AppLanguage.forContext(this).locale
    private lateinit var trackStore: TrackStore
    private lateinit var detourStore: DetourSessionStore
    private lateinit var recordingRouteStore: RecordingRouteStore
    private lateinit var offlineMapDownloader: OfflineMapDownloader
    private lateinit var fitnessPreferences: FitnessPreferences
    private lateinit var activityPreferences: ActivityPreferences
    private lateinit var navigationPreferences: NavigationPreferences
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var map: MapLibreMap? = null
    private var mapStyle: Style? = null
    private var importedTrack: GpxTrack? = null
    private var displayedRouteTrack: GpxTrack? = null
    private var detourOverlayTrack: GpxTrack? = null
    private var closureSegments: List<List<TrackPoint>> = emptyList()
    private var offlineMapIdentityTrack: GpxTrack? = null
    private var importedTrackReference: String? = null
    private var activeDetour = false
    private var activeRouteAdjustmentKind: RouteAdjustmentKind? = null
    private var latestSnapshot = TrackingSnapshot()
    private var pendingRecordingStart = false
    private var pendingCenterRequest = false
    private var selectedActivityType = ActivityType.HIKING
    private var followLocation = true
    private var mapOrientationMode = MapOrientationMode.NORTH_UP
    private var lastHeadingUpEaseMillis = 0L
    private var renderedCompassHeadingUp: Boolean? = null
    private var initialRegionFramingComplete = false
    private var offlineDownloadInProgress = false
    private var latestLocatedPoint: TrackPoint? = null
    private var currentRouteStatusMessage: CharSequence? = null
    private var currentRouteStatusColorRes = R.color.forest_900
    private var routeProgressTracker: RouteProgressTracker? = null
    private var routeRejoinAdvisor: RouteRejoinAdvisor? = null
    private val headingSmoother = HeadingSmoother()
    private var latestCompassHeadingDegrees: Float? = null
    private var compassAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var visibleLocationUpdatesActive = false
    private lateinit var recordingSheetBehavior: BottomSheetBehavior<com.google.android.material.card.MaterialCardView>
    private var recordingDrawerState = BottomSheetBehavior.STATE_COLLAPSED
    private var recordingDrawerInitializedForSession = false
    private var recordingDrawerTargetHeight = 0
    private var recordingDrawerGeometryReady = false
    private var pendingRecordingDrawerState: Int? = null
    private var pendingRecordingPeekHeight: Int? = null
    private var debugSnapshotOverride = false
    private var arrivalPromptShownForSessionId: Long? = null
    private var arrivalPromptDialog: androidx.appcompat.app.AlertDialog? = null
    private var lastRenderedRecordingState = RecordingState.IDLE
    private var lastRenderedLiveTrack: GpxTrack? = null
    private var recordingElevationSource: GpxTrack? = null
    private var recordingElevationUsesPlannedRoute = false
    private val speedSmoother = SpeedSmoother()
    private val gpsQualityWarningMonitor = GpsQualityWarningMonitor()
    private val compassRotationMatrix = FloatArray(9)
    private val compassOrientation = FloatArray(3)
    private val routeEndpointMarkers = mutableListOf<Marker>()
    private val routeStartIcon: Icon by lazy { createRouteEndpointIcon("S", R.color.forest_700) }
    private val routeEndIcon: Icon by lazy { createRouteEndpointIcon("Z", R.color.warning) }
    private val locationRequestSignals = mutableListOf<CancellationSignal>()
    private val restoreRouteStatusRunnable = Runnable {
        if (!::binding.isInitialized) return@Runnable
        renderLocationStatus(
            latestSnapshot.latestObservedPoint ?: latestSnapshot.latestPoint ?: latestLocatedPoint,
            latestSnapshot.gpsGapActive,
        )
    }
    private val recordingTimeTickRunnable = object : Runnable {
        override fun run() {
            if (!::binding.isInitialized) return
            renderLiveRecordingTimes()
            val now = SystemClock.elapsedRealtime()
            binding.root.postDelayed(this, RECORDING_TIME_TICK_MILLIS - now % RECORDING_TIME_TICK_MILLIS)
        }
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
        if (!locationGranted) {
            if (startRecording) {
                toast(getString(R.string.recording_location_permission_denied))
            } else if (centerUser) {
                toast(getString(R.string.map_location_permission_denied))
            }
            return@registerForActivityResult
        }
        startVisibleLocationUpdates()
        if (centerUser) focusOnUser()
        if (startRecording) {
            requestRecordingNotificationPermission()
            sendTrackingAction(TrackingService.ACTION_START, startForeground = true)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showRecordingNotificationSettingsDialog()
    }

    private val detourPlannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) applyPersistedDetour(announce = true)
    }

    private val recordingRouteEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) applyPersistedRecordingRoute(announce = true)
    }

    private val tourEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val reference = result.data
            ?.getStringExtra(RoutePlannerActivity.EXTRA_EDIT_TOUR_REFERENCE)
            ?: return@registerForActivityResult
        openStoredTour(reference)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialRegionFramingComplete = savedInstanceState != null
        arrivalPromptShownForSessionId = savedInstanceState
            ?.takeIf { it.containsKey(KEY_ARRIVAL_PROMPT_SESSION) }
            ?.getLong(KEY_ARRIVAL_PROMPT_SESSION)
        mapOrientationMode = savedInstanceState
            ?.getString(KEY_MAP_ORIENTATION_MODE)
            ?.let { stored -> runCatching { MapOrientationMode.valueOf(stored) }.getOrNull() }
            ?: MapOrientationMode.NORTH_UP
        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            val safeBottom = maxOf(
                systemBars.bottom,
                navigationBars.bottom,
                tappable.bottom,
                navigationBarHeightFallback(),
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, safeBottom)
            binding.recordingCard.post(::updateRecordingDrawerGeometry)
            insets
        }
        trackStore = TrackStore(this)
        detourStore = DetourSessionStore(this)
        recordingRouteStore = RecordingRouteStore(this)
        offlineMapDownloader = OfflineMapDownloader(this, MAP_STYLE_URL)
        fitnessPreferences = FitnessPreferences(this)
        activityPreferences = ActivityPreferences(this)
        navigationPreferences = NavigationPreferences(this)
        clearLegacyCompassCorrection()
        selectedActivityType = activityPreferences.defaultType
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        binding.mapView.onCreate(savedInstanceState)
        binding.actionsCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncOverlayPositions()
        }
        binding.planningBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncOverlayPositions()
        }
        binding.recordingCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRecordingDrawerGeometry()
            syncOverlayPositions()
        }
        binding.recordingCollapsedContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRecordingDrawerGeometry()
        }
        binding.recordingScrollableContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRecordingDrawerGeometry()
        }

        setupRecordingDrawer()
        setupMap()
        setupActions()
        renderCompassFab()
        observeTracking()
        restoreActiveRecording()
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.action == ACTION_DEBUG_SCENARIO) {
            applyDebugScenario(intent.getStringExtra(EXTRA_DEBUG_SCENARIO).orEmpty())
            return
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_DEBUG_START_RECORDING, false) == true) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_START)
                    .putExtra(TrackingService.EXTRA_ROUTE_NAME, intent.getStringExtra(EXTRA_DEBUG_ROUTE_NAME))
                    .putExtra(TrackingService.EXTRA_ROUTE_REFERENCE, intent.getStringExtra(EXTRA_TOUR_REFERENCE))
                    .putExtra(
                        TrackingService.EXTRA_ACTIVITY_TYPE,
                        intent.getStringExtra(EXTRA_DEBUG_ACTIVITY_TYPE),
                    )
                    .putExtra(TrackingService.EXTRA_DEBUG_SIMULATION, true),
            )
            intent.removeExtra(EXTRA_DEBUG_START_RECORDING)
            intent.removeExtra(EXTRA_DEBUG_ROUTE_NAME)
            intent.removeExtra(EXTRA_DEBUG_ACTIVITY_TYPE)
        }
        intent?.getStringExtra(EXTRA_TOUR_REFERENCE)?.let {
            initialRegionFramingComplete = true
            openStoredTour(
                reference = it,
                askForOfflineDownload = intent.getBooleanExtra(EXTRA_OFFER_OFFLINE_MAP, false),
            )
            intent.removeExtra(EXTRA_TOUR_REFERENCE)
            intent.removeExtra(EXTRA_OFFER_OFFLINE_MAP)
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let {
            initialRegionFramingComplete = true
            importGpx(it)
        }
    }

    private fun setupMap() {
        binding.mapView.getMapAsync { readyMap ->
            map = readyMap.apply {
                uiSettings.isCompassEnabled = false
                uiSettings.isAttributionEnabled = true
                uiSettings.isLogoEnabled = true
                installCameraGestureListeners(this)
                addOnCameraMoveListener(::renderCompassFab)
                addOnCameraIdleListener(::renderCompassFab)
                addOnMapClickListener(::showMapPoi)
            }
            readyMap.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                MapStyleLocalizer.localize(style, AppLanguage.forContext(this))
                mapStyle = style
                renderCompassFab()
                installTrackLayers(style)
                redrawTracks()
                frameInitialRegionIfNeeded(displayedPosition())
                if (debugSnapshotOverride) importedTrack?.let(::fitTrack)
            }
        }
    }

    /**
     * Only panning hands the camera back to the hiker. Zoom, rotation and tilt keep following the
     * position, so a pinch does not silently freeze the viewport somewhere behind the hiker.
     */
    private fun installCameraGestureListeners(readyMap: MapLibreMap) {
        readyMap.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                // Panning also pauses heading-up steering; the mode itself survives.
                followLocation = false
                initialRegionFramingComplete = true
                renderCompassFab()
            }

            override fun onMove(detector: MoveGestureDetector) = Unit

            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        })
        readyMap.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                initialRegionFramingComplete = true
            }

            override fun onScale(detector: StandardScaleGestureDetector) = Unit

            override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
        })
        readyMap.addOnRotateListener(object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: RotateGestureDetector) {
                initialRegionFramingComplete = true
            }

            override fun onRotate(detector: RotateGestureDetector) = Unit

            override fun onRotateEnd(detector: RotateGestureDetector) = Unit
        })
        readyMap.addOnShoveListener(object : MapLibreMap.OnShoveListener {
            override fun onShoveBegin(detector: ShoveGestureDetector) {
                initialRegionFramingComplete = true
            }

            override fun onShove(detector: ShoveGestureDetector) = Unit

            override fun onShoveEnd(detector: ShoveGestureDetector) = Unit
        })
    }

    private fun applyDebugScenario(rawScenario: String) {
        val scenario = rawScenario.ifBlank { "route-expanded" }.lowercase()
        debugSnapshotOverride = true
        recordingDrawerInitializedForSession = true
        activeDetour = scenario.contains("detour")
        activeRouteAdjustmentKind = RouteAdjustmentKind.DETOUR.takeIf { activeDetour }

        val route = when {
            scenario.startsWith("free") -> null
            scenario.contains("detour-round") -> debugRoundRoute()
            else -> debugRoute()
        }
        val recordedTrack = debugRecordedTrack(route)
        val latest = recordedTrack.points.lastOrNull()
        val observed = if (scenario.contains("off-route") && latest != null) {
            latest.copy(longitude = latest.longitude + 0.004, accuracyMeters = 12f)
        } else {
            latest
        }
        if (route == null) {
            importedTrack = null
            displayedRouteTrack = null
            detourOverlayTrack = null
            closureSegments = emptyList()
            offlineMapIdentityTrack = null
            importedTrackReference = null
            routeProgressTracker = null
            routeRejoinAdvisor = null
            redrawTracks()
        } else if (scenario.contains("detour")) {
            val routePath = de.wandern.app.model.RoutePath(route)
            val departureDistance = latest?.let(routePath::nearestDistanceAlongRoute)
                ?: routePath.totalDistanceMeters * 0.4
            val corridor = DetourPlanner.corridor(route, departureDistance, 420.0)
            val rejoinDistance = DetourPlanner.rejoinDistances(route, corridor)
                .getOrElse(1) { DetourPlanner.rejoinDistances(route, corridor).first() }
            val departure = routePath.pointAt(departureDistance)
            val rejoin = routePath.pointAt(rejoinDistance)
            val detour = GpxTrack(
                name = "Debug · Umleitung",
                segments = listOf(
                    listOf(
                        departure,
                        TrackPoint(
                            latitude = departure.latitude + 0.006,
                            longitude = departure.longitude - 0.004,
                            elevationMeters = departure.elevationMeters,
                        ),
                        TrackPoint(
                            latitude = rejoin.latitude + 0.005,
                            longitude = rejoin.longitude - 0.003,
                            elevationMeters = rejoin.elevationMeters,
                        ),
                        rejoin,
                    ),
                ),
                activityType = route.activityType,
            )
            val candidate = DetourPlanner.combine(
                route = route,
                currentProgressMeters = departureDistance,
                corridor = corridor,
                detour = detour,
                rejoinDistanceMeters = rejoinDistance,
            )
            closureSegments = listOf(corridor.points)
            displayTrack(
                track = candidate.track,
                reference = null,
                askForOfflineDownload = false,
                announce = false,
                frameTrack = true,
                displayedRoute = DetourPlanner.originalRouteOutsideDetour(
                    route,
                    departureDistance,
                    rejoinDistance,
                ),
                detourOverlay = candidate.detourTrack,
            )
        } else {
            displayTrack(
                track = route,
                reference = null,
                askForOfflineDownload = false,
                announce = false,
                frameTrack = true,
            )
        }
        val paused = scenario.contains("paused")
        val snapshot = TrackingSnapshot(
            state = if (paused) RecordingState.PAUSED else RecordingState.RECORDING,
            track = recordedTrack,
            stats = TrackStats(
                distanceMeters = when {
                    scenario.contains("short") -> 420.0
                    route == null -> 4_860.0
                    else -> 5_420.0
                },
                durationMillis = 4_218_000L,
                movingDurationMillis = 3_774_000L,
                pauseDurationMillis = 444_000L,
                pauseCount = 2,
                ascentMeters = 286.0,
                descentMeters = 174.0,
                averageSpeedMetersPerSecond = 1.44,
                paceSecondsPerKilometer = 694.0,
                currentSlopePercent = 6.4,
                pointCount = recordedTrack.points.size,
            ),
            latestPoint = latest,
            latestObservedPoint = observed,
            gpsGapActive = scenario.contains("gps-gap"),
            autoPaused = scenario.contains("auto-pause"),
            routeDeviationMeters = if (scenario.contains("off-route")) 92.0 else 8.0,
            confirmedOffRoute = scenario.contains("off-route"),
            activityType = ActivityType.HIKING,
            capturedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            movementTimeRunning = !paused,
            navigationGuidance = when {
                route == null -> null
                scenario.contains("arrived") -> NavigationGuidance(
                    maneuver = NavigationManeuver(
                        type = NavigationManeuverType.ARRIVE,
                        point = route.points.last(),
                        distanceAlongRouteMeters = 0.0,
                    ),
                    distanceMeters = 6.0,
                )
                scenario.contains("navigation") -> NavigationGuidance(
                    maneuver = NavigationManeuver(
                        type = NavigationManeuverType.RIGHT,
                        point = route.points[60],
                        distanceAlongRouteMeters = 0.0,
                        turnAngleDegrees = 90.0,
                    ),
                    distanceMeters = 85.0,
                )
                else -> null
            },
        )
        latestSnapshot = snapshot
        renderSnapshot(snapshot)
        binding.recordingInfoCarousel.showPage(
            if (scenario.contains("elevation")) RECORDING_PAGE_ELEVATION else RECORDING_PAGE_STATS,
            animate = false,
        )
        val drawerState = if (scenario.contains("collapsed")) {
            BottomSheetBehavior.STATE_COLLAPSED
        } else {
            BottomSheetBehavior.STATE_EXPANDED
        }
        binding.recordingCard.post {
            updateRecordingDrawerGeometry()
            setRecordingDrawerState(drawerState)
            importedTrack?.let(::fitTrack)
        }
        intent.removeExtra(EXTRA_DEBUG_SCENARIO)
    }

    private fun debugRoute(): GpxTrack {
        val points = (0..120).map { index ->
            val fraction = index / 120.0
            TrackPoint(
                latitude = 48.805 - fraction * 0.092 + kotlin.math.sin(fraction * Math.PI * 3.0) * 0.006,
                longitude = 8.205 + fraction * 0.065 + kotlin.math.sin(fraction * Math.PI * 2.0) * 0.010,
                elevationMeters = 132.0 + fraction * 470.0 + kotlin.math.sin(fraction * Math.PI * 5.0) * 55.0,
            )
        }
        return GpxTrack(
            name = "Debug · Schwarzwaldroute",
            segments = listOf(points),
            activityType = ActivityType.HIKING,
        )
    }

    private fun debugRoundRoute(): GpxTrack {
        val centerLatitude = 48.765
        val centerLongitude = 8.245
        val points = (0..120).map { index ->
            val angle = index / 120.0 * Math.PI * 2.0
            TrackPoint(
                latitude = centerLatitude + kotlin.math.sin(angle) * 0.035,
                longitude = centerLongitude + kotlin.math.cos(angle) * 0.048,
                elevationMeters = 320.0 + kotlin.math.sin(angle * 2.0) * 135.0,
            )
        }
        return GpxTrack(
            name = "Debug · Rundweg",
            segments = listOf(points),
            activityType = ActivityType.HIKING,
        )
    }

    private fun debugRecordedTrack(route: GpxTrack?): GpxTrack {
        val points = if (route != null) {
            route.points.take(52)
        } else {
            (0..64).map { index ->
                val fraction = index / 64.0
                TrackPoint(
                    latitude = 48.765 + fraction * 0.032 + kotlin.math.sin(fraction * Math.PI * 4.0) * 0.003,
                    longitude = 8.245 + fraction * 0.025,
                    elevationMeters = 155.0 + fraction * 220.0 + kotlin.math.sin(fraction * Math.PI * 3.0) * 38.0,
                )
            }
        }
        val now = System.currentTimeMillis()
        return GpxTrack(
            name = "Debug · laufende Aufzeichnung",
            segments = listOf(
                points.mapIndexed { index, point ->
                    point.copy(
                        timeMillis = now - (points.lastIndex - index) * 60_000L,
                        accuracyMeters = 9f,
                        speedMetersPerSecond = 1.5f,
                    )
                },
            ),
            activityType = ActivityType.HIKING,
        )
    }

    private fun showMapPoi(coordinate: LatLng): Boolean {
        val readyMap = map ?: return false
        if (mapStyle == null) return false
        return MapPoiDialog.show(
            activity = this,
            map = readyMap,
            coordinate = coordinate,
            userPosition = displayedPosition(),
            distanceFormatter = ::formatRemainingDistance,
        )
    }

    private fun installTrackLayers(style: Style) {
        // Lines go below the map labels so street names stay readable; markers, position circles
        // and the direction arrows are added afterwards and stay on top.
        val labelLayerId = MapLayerOrder.firstLabelLayerId(style)
        fun addLineLayer(layer: LineLayer) = MapLayerOrder.addLayerBelowLabels(style, layer, labelLayerId)
        style.addSource(GeoJsonSource(LIVE_TRACK_SOURCE, EMPTY_FEATURE_COLLECTION))
        addLineLayer(
            LineLayer(LIVE_TRACK_LAYER, LIVE_TRACK_SOURCE).withProperties(
                lineColor(Color.parseColor("#F26B38")),
                lineWidth(5f),
                lineOpacity(0.95f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(INTERPOLATED_TRACK_SOURCE, EMPTY_FEATURE_COLLECTION))
        addLineLayer(
            LineLayer(INTERPOLATED_TRACK_LAYER, INTERPOLATED_TRACK_SOURCE).withProperties(
                lineColor(Color.parseColor("#F2A65A")),
                lineWidth(5f),
                lineOpacity(0.95f),
                lineDasharray(arrayOf(1.4f, 1.4f)),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(ROUTE_SOURCE, EMPTY_FEATURE_COLLECTION))
        addLineLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineColor(Color.parseColor("#1677FF")),
                lineWidth(6f),
                lineOpacity(0.7f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(CLOSURE_SOURCE, EMPTY_FEATURE_COLLECTION))
        addLineLayer(
            LineLayer(CLOSURE_HALO_LAYER, CLOSURE_SOURCE).withProperties(
                lineColor(Color.WHITE),
                lineWidth(12f),
                lineOpacity(0.75f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        addLineLayer(
            LineLayer(CLOSURE_LAYER, CLOSURE_SOURCE).withProperties(
                lineColor(Color.parseColor("#C44431")),
                lineWidth(7f),
                lineOpacity(0.85f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        RouteDirectionIndicator.addToStyle(
            context = this,
            style = style,
            sourceId = ROUTE_SOURCE,
            layerId = ROUTE_DIRECTION_LAYER,
            iconId = ROUTE_DIRECTION_ICON,
        )
        style.addSource(GeoJsonSource(DETOUR_SOURCE, EMPTY_FEATURE_COLLECTION))
        addLineLayer(
            LineLayer(DETOUR_LAYER, DETOUR_SOURCE).withProperties(
                lineColor(Color.parseColor("#F28C28")),
                lineWidth(7f),
                lineOpacity(0.85f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
        style.addSource(GeoJsonSource(POSITION_SOURCE, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            CircleLayer(POSITION_HALO_LAYER, POSITION_SOURCE).withProperties(
                circleColor(Color.parseColor("#1677FF")),
                circleRadius(16f),
                circleOpacity(0.18f),
            ),
        )
        style.addImage(POSITION_DIRECTION_ICON, createPositionDirectionIcon())
        style.addSource(GeoJsonSource(POSITION_DIRECTION_SOURCE, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            SymbolLayer(POSITION_DIRECTION_LAYER, POSITION_DIRECTION_SOURCE).withProperties(
                iconImage(POSITION_DIRECTION_ICON),
                iconRotate(0f),
                iconRotationAlignment("map"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
        style.addLayer(
            CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                circleColor(Color.parseColor("#1677FF")),
                circleRadius(9f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(3f),
            ),
        )
    }

    private fun setupActions() {
        binding.searchPlaceButton.setOnClickListener {
            val intent = Intent(this, RoutePlannerActivity::class.java)
                .putExtra(RoutePlannerActivity.EXTRA_OPEN_SEARCH, true)
            displayedPosition()?.let { position ->
                intent
                    .putExtra(RoutePlannerActivity.EXTRA_SEARCH_REFERENCE_LATITUDE, position.latitude)
                    .putExtra(RoutePlannerActivity.EXTRA_SEARCH_REFERENCE_LONGITUDE, position.longitude)
            }
            startActivity(intent)
        }
        binding.planRouteButton.setOnClickListener {
            startActivity(Intent(this, RoutePlannerActivity::class.java))
        }
        binding.toursButton.setOnClickListener {
            startActivity(Intent(this, TourLibraryActivity::class.java))
        }
        binding.recordButton.setOnClickListener {
            if (latestSnapshot.state == RecordingState.IDLE || latestSnapshot.state == RecordingState.FINISHED) {
                requestRecordingStart()
            }
        }
        binding.recordingExpandButton.setOnClickListener {
            cycleRecordingDrawer()
        }
        binding.recordingPauseButton.setOnClickListener { toast(getString(R.string.pause_hold_hint)) }
        binding.recordingPauseButton.setOnLongClickListener {
            sendTrackingAction(TrackingService.ACTION_PAUSE)
            true
        }
        binding.recordingResumeButton.setOnClickListener {
            sendTrackingAction(TrackingService.ACTION_RESUME)
        }
        binding.recordingFinishButton.setOnClickListener { confirmStopRecording() }
        binding.recordingDiscardButton.setOnClickListener { confirmDiscardRecording() }
        binding.recordingRouteButton.setOnClickListener { openRecordingRouteEditor() }
        binding.recordingRejoinButton.setOnClickListener { openRouteRejoinPlanner() }
        binding.routeRejoinBanner.setOnClickListener { openRouteRejoinPlanner() }
        binding.recordingDetourButton.setOnClickListener { openDetourPlanner() }
        binding.recordingDetourFab.setOnClickListener { openDetourPlanner() }
        binding.recordingUndoDetourButton.setOnClickListener { undoActiveDetour() }
        binding.moreButton.setOnClickListener { showMoreMenu() }
        binding.mapSettingsFab.setOnClickListener { showMapSettingsMenu() }
        binding.compassFab.setOnClickListener { switchMapOrientation() }
        binding.centerButton.setOnClickListener {
            requestCenterOnUser()
            maybeShowCompassCalibrationHint()
        }
        binding.centerButton.setOnLongClickListener {
            startActivity(Intent(this, CompassCalibrationActivity::class.java))
            true
        }
        renderRouteDependentActions()
    }

    private fun setupRecordingDrawer() {
        recordingSheetBehavior = BottomSheetBehavior.from(binding.recordingCard).apply {
            isDraggable = true
            isHideable = false
            skipCollapsed = false
            isFitToContents = true
            state = BottomSheetBehavior.STATE_COLLAPSED
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    syncOverlayPositions()
                }

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_DRAGGING ||
                        newState == BottomSheetBehavior.STATE_SETTLING
                    ) return
                    recordingDrawerState = newState
                    updateRecordingDrawerGeometry()
                    renderRecordingDrawerChrome()
                    scheduleOverlayPositionSync()
                }
            })
        }
        binding.recordingExpandedGroup.contentScrollingEnabled = true
        binding.recordingCollapsedContent.setOnClickListener {
            cycleRecordingDrawer()
        }
        listOf(
            binding.recordingExpandButton,
            binding.recordingPauseButton,
            binding.recordingResumeButton,
            binding.recordingFinishButton,
        ).forEach { control ->
            control.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    control.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
        binding.recordingInfoCarousel.onPageChanged = ::renderRecordingCarouselPage
        binding.recordingPageDotElevation.setOnClickListener {
            binding.recordingInfoCarousel.showPage(RECORDING_PAGE_ELEVATION)
        }
        binding.recordingPageDotStats.setOnClickListener {
            binding.recordingInfoCarousel.showPage(RECORDING_PAGE_STATS)
        }
        renderRecordingCarouselPage(RECORDING_PAGE_STATS)
    }

    private fun cycleRecordingDrawer() {
        val nextState = if (recordingDrawerState == BottomSheetBehavior.STATE_COLLAPSED) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_COLLAPSED
        }
        setRecordingDrawerState(nextState)
    }

    private fun setRecordingDrawerState(state: Int) {
        if (!::recordingSheetBehavior.isInitialized) return
        val stableState = if (state == BottomSheetBehavior.STATE_COLLAPSED) {
            BottomSheetBehavior.STATE_COLLAPSED
        } else {
            BottomSheetBehavior.STATE_EXPANDED
        }
        recordingDrawerState = stableState
        renderRecordingDrawerChrome()
        if (!recordingDrawerGeometryReady) {
            pendingRecordingDrawerState = stableState
            binding.recordingCard.post(::updateRecordingDrawerGeometry)
            return
        }
        applyRecordingDrawerState(stableState)
    }

    private fun applyRecordingDrawerState(state: Int) {
        pendingRecordingDrawerState = null
        if (recordingSheetBehavior.state != state) {
            recordingSheetBehavior.state = state
        }
        scheduleOverlayPositionSync()
    }

    private fun updateRecordingDrawerGeometry() {
        if (!::recordingSheetBehavior.isInitialized || binding.recordingCard.visibility != View.VISIBLE) {
            return
        }
        val hostHeight = binding.recordingSheetHost.height
        if (hostHeight <= 0) return
        val collapsedContentHeight = binding.recordingCollapsedContent.height.coerceAtLeast(dp(150))
        val behaviorState = recordingSheetBehavior.state
        if (behaviorState == BottomSheetBehavior.STATE_DRAGGING ||
            behaviorState == BottomSheetBehavior.STATE_SETTLING
        ) {
            pendingRecordingPeekHeight = collapsedContentHeight
        } else if (recordingSheetBehavior.peekHeight != collapsedContentHeight) {
            recordingSheetBehavior.setPeekHeight(collapsedContentHeight, false)
            pendingRecordingPeekHeight = null
        }

        if (recordingDrawerTargetHeight == 0) {
            val expandedContentHeight = binding.recordingScrollableContent.measuredHeight
            if (expandedContentHeight <= 0) return
            // Leave enough map space for the 48 dp location FAB above the fully expanded sheet,
            // including its minimum top inset on compact displays.
            val maximumHeight = (hostHeight - dp(60)).coerceAtLeast(collapsedContentHeight)
            // Freeze the expanded height for this recording session. Live banners and action
            // changes may adjust the collapsed peek or overflow, but never the animation target.
            recordingDrawerTargetHeight = (collapsedContentHeight + expandedContentHeight)
                .coerceAtMost(maximumHeight)
            recordingDrawerGeometryReady = false
            binding.recordingCard.layoutParams = binding.recordingCard.layoutParams.apply {
                height = recordingDrawerTargetHeight
            }
            return
        }

        if (binding.recordingCard.height != recordingDrawerTargetHeight) return
        recordingDrawerGeometryReady = true
        pendingRecordingPeekHeight?.let { pendingPeekHeight ->
            if (recordingSheetBehavior.peekHeight != pendingPeekHeight) {
                recordingSheetBehavior.setPeekHeight(pendingPeekHeight, false)
            }
            pendingRecordingPeekHeight = null
        }
        pendingRecordingDrawerState?.let(::applyRecordingDrawerState)
        scheduleOverlayPositionSync()
    }

    private fun resetRecordingDrawerGeometry() {
        val alreadyReset = recordingDrawerTargetHeight == 0 &&
            !recordingDrawerGeometryReady &&
            pendingRecordingDrawerState == null &&
            pendingRecordingPeekHeight == null &&
            binding.recordingCard.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT
        if (alreadyReset) return
        recordingDrawerTargetHeight = 0
        recordingDrawerGeometryReady = false
        pendingRecordingDrawerState = null
        pendingRecordingPeekHeight = null
        binding.recordingCard.layoutParams = binding.recordingCard.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun renderRecordingDrawerChrome() {
        val expanded = recordingDrawerState == BottomSheetBehavior.STATE_EXPANDED
        // Keep the expanded content laid out while collapsed, so it follows the finger immediately
        // instead of popping in only after BottomSheetBehavior reaches its final state.
        binding.recordingAdvancedActions.visibility = View.VISIBLE
        binding.recordingExpandButton.setIconResource(
            if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less,
        )
        binding.recordingExpandButton.contentDescription = getString(
            if (expanded) R.string.hide_recording_details else R.string.show_recording_details,
        )
    }

    private fun renderRecordingCarouselPage(page: Int) {
        binding.recordingPageDotElevation.setBackgroundResource(
            if (page == RECORDING_PAGE_ELEVATION) R.drawable.carousel_dot_selected
            else R.drawable.carousel_dot_unselected,
        )
        binding.recordingPageDotStats.setBackgroundResource(
            if (page == RECORDING_PAGE_STATS) R.drawable.carousel_dot_selected
            else R.drawable.carousel_dot_unselected,
        )
    }

    private fun observeTracking() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                TrackingService.snapshots.collect { snapshot ->
                    if (debugSnapshotOverride) return@collect
                    latestSnapshot = snapshot
                    renderSnapshot(snapshot)
                    presentFinishedRecording(snapshot)
                }
            }
        }
    }

    private fun presentFinishedRecording(snapshot: TrackingSnapshot) {
        if (snapshot.state != RecordingState.FINISHED) return
        val reference = snapshot.savedTourReference ?: return
        val preferences = getSharedPreferences(COMPLETION_PRESENTATION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getString(KEY_LAST_PRESENTED_RECORDING, null) == reference) return
        preferences.edit().putString(KEY_LAST_PRESENTED_RECORDING, reference).apply()
        openTourDetails(reference)
    }

    private fun restoreActiveRecording() {
        val activeSession = trackStore.activeSession() ?: return
        refreshSessionClosures()
        if (detourStore.load(activeSession.id) != null) {
            applyPersistedDetour(announce = false)
        } else if (recordingRouteStore.load(activeSession.id) != null) {
            applyPersistedRecordingRoute(announce = false)
        } else {
            activeSession.routeReference?.let(::restoreActiveRoute)
        }
        val intent = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_RESTORE)
        if (activeSession.state == RecordingState.RECORDING) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        binding.root.post(::requestRecordingNotificationPermission)
    }

    private fun restoreActiveRoute(
        reference: String,
        entryMode: RouteEntryMode = RouteEntryMode.OFFICIAL_START,
    ) {
        if (importedTrackReference == reference) return
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { trackStore.loadStoredTrack(reference) }
            }
            result.onSuccess { track ->
                val activeRouteReference = trackStore.activeSession()?.routeReference
                if (activeRouteReference == reference) {
                    activeDetour = false
                    activeRouteAdjustmentKind = null
                    displayTrack(
                        track = track,
                        reference = reference,
                        askForOfflineDownload = false,
                        announce = false,
                        frameTrack = false,
                    )
                    routeProgressTracker = RouteProgressTracker(track, entryMode)
                    renderRouteProgress(latestSnapshot.latestPoint ?: latestLocatedPoint)
                }
            }.onFailure {
                Log.e(LOG_TAG, "Active route could not be restored: $reference", it)
            }
        }
    }

    /** Sections reported as blocked in this recording stay visible while the detour is active. */
    private fun refreshSessionClosures() {
        val activeSession = trackStore.activeSession()
        closureSegments = activeSession
            ?.let { session -> detourStore.closures(session.id).map { it.points } }
            .orEmpty()
    }

    private fun applyPersistedDetour(announce: Boolean) {
        val activeSession = trackStore.activeSession() ?: return
        val detour = detourStore.load(activeSession.id) ?: return
        refreshSessionClosures()
        lifecycleScope.launch {
            val originalRoute = withContext(Dispatchers.IO) {
                if (detour.restoresRecordingRoute) {
                    recordingRouteStore.load(activeSession.id)?.route
                } else {
                    detour.originalRouteReference?.let { reference ->
                        runCatching { trackStore.loadStoredTrack(reference) }.getOrNull()
                    }
                }
            }
            val preservedOriginal = if (originalRoute != null && detour.detourTrack != null) {
                DetourPlanner.originalRouteOutsideDetour(
                    route = originalRoute,
                    departureDistanceMeters = detour.departureDistanceMeters,
                    rejoinDistanceMeters = detour.rejoinDistanceMeters,
                )
            } else {
                detour.route
            }
            activeDetour = true
            activeRouteAdjustmentKind = detour.kind
            displayTrack(
                track = detour.route,
                reference = detour.originalRouteReference,
                askForOfflineDownload = false,
                announce = false,
                frameTrack = false,
                displayedRoute = preservedOriginal,
                detourOverlay = detour.detourTrack,
            )
            routeProgressTracker = RouteProgressTracker(detour.route, RouteEntryMode.NEAREST_POINT)
            renderRouteProgress(latestSnapshot.latestPoint ?: latestLocatedPoint)
            sendTrackingAction(TrackingService.ACTION_UPDATE_NAVIGATION_ROUTE)
            renderRecordingPanelState(latestSnapshot)
            if (announce) {
                showRouteStatus(
                    getString(
                        if (detour.kind == RouteAdjustmentKind.REJOIN) {
                            R.string.route_rejoin_saved
                        } else {
                            R.string.detour_saved
                        },
                    ),
                    R.color.forest_900,
                    SUCCESS_BADGE_MILLIS,
                )
            }
        }
    }

    private fun applyPersistedRecordingRoute(announce: Boolean) {
        val activeSession = trackStore.activeSession() ?: return
        val activeRoute = recordingRouteStore.load(activeSession.id) ?: return
        refreshSessionClosures()
        activeDetour = false
        activeRouteAdjustmentKind = null
        displayTrack(
            track = activeRoute.route,
            reference = activeSession.routeReference,
            askForOfflineDownload = false,
            announce = false,
            frameTrack = false,
        )
        routeProgressTracker = RouteProgressTracker(activeRoute.route, RouteEntryMode.NEAREST_POINT)
        renderRouteProgress(latestSnapshot.latestPoint ?: latestLocatedPoint)
        sendTrackingAction(TrackingService.ACTION_UPDATE_NAVIGATION_ROUTE)
        renderRecordingPanelState(latestSnapshot)
        if (announce) {
            showRouteStatus(
                getString(R.string.navigation_route_updated),
                R.color.forest_900,
                SUCCESS_BADGE_MILLIS,
            )
        }
    }

    private fun openRecordingRouteEditor() {
        val session = trackStore.activeSession() ?: run {
            toast(getString(R.string.recording_no_longer_active))
            return
        }
        val point = latestSnapshot.latestPoint
            ?: latestSnapshot.latestObservedPoint
            ?: latestLocatedPoint
        if (importedTrack == null && point == null) {
            toast(getString(R.string.recording_route_needs_position))
            return
        }
        val intent = Intent(this, RoutePlannerActivity::class.java)
            .putExtra(RoutePlannerActivity.EXTRA_RECORDING_SESSION_ID, session.id)
        point?.let {
            intent
                .putExtra(RoutePlannerActivity.EXTRA_RECORDING_LATITUDE, it.latitude)
                .putExtra(RoutePlannerActivity.EXTRA_RECORDING_LONGITUDE, it.longitude)
                .putExtra(RoutePlannerActivity.EXTRA_SEARCH_REFERENCE_LATITUDE, it.latitude)
                .putExtra(RoutePlannerActivity.EXTRA_SEARCH_REFERENCE_LONGITUDE, it.longitude)
        }
        recordingRouteEditorLauncher.launch(intent)
    }

    private fun openDetourPlanner() {
        val session = trackStore.activeSession()
        val point = latestSnapshot.latestPoint
            ?: latestSnapshot.latestObservedPoint
            ?: latestLocatedPoint
        if (session == null || importedTrack == null) {
            toast(getString(R.string.detour_needs_route))
            return
        }
        if (point == null || (point.accuracyMeters != null && point.accuracyMeters > 80f)) {
            toast(getString(R.string.detour_needs_position))
            return
        }
        val progress = routeProgressTracker?.currentOrInitial()?.distanceAlongRouteMeters ?: 0.0
        detourPlannerLauncher.launch(
            Intent(this, DetourPlannerActivity::class.java)
                .putExtra(DetourPlannerActivity.EXTRA_SESSION_ID, session.id)
                .putExtra(DetourPlannerActivity.EXTRA_LATITUDE, point.latitude)
                .putExtra(DetourPlannerActivity.EXTRA_LONGITUDE, point.longitude)
                .putExtra(DetourPlannerActivity.EXTRA_PROGRESS_METERS, progress),
        )
    }

    private fun openRouteRejoinPlanner() {
        val session = trackStore.activeSession()
        val point = latestSnapshot.latestPoint
            ?: latestSnapshot.latestObservedPoint
            ?: latestLocatedPoint
        if (session == null || importedTrack == null) {
            toast(getString(R.string.detour_needs_route))
            return
        }
        if (point == null || (point.accuracyMeters != null && point.accuracyMeters > 80f)) {
            toast(getString(R.string.detour_needs_position))
            return
        }
        val progress = routeProgressTracker?.currentOrInitial()?.distanceAlongRouteMeters ?: 0.0
        detourPlannerLauncher.launch(
            Intent(this, DetourPlannerActivity::class.java)
                .putExtra(DetourPlannerActivity.EXTRA_SESSION_ID, session.id)
                .putExtra(DetourPlannerActivity.EXTRA_LATITUDE, point.latitude)
                .putExtra(DetourPlannerActivity.EXTRA_LONGITUDE, point.longitude)
                .putExtra(DetourPlannerActivity.EXTRA_PROGRESS_METERS, progress)
                .putExtra(DetourPlannerActivity.EXTRA_MODE, DetourPlannerActivity.MODE_REJOIN),
        )
    }

    private fun undoActiveDetour() {
        val session = trackStore.activeSession() ?: return
        val detour = detourStore.load(session.id) ?: return
        detourStore.clear(session.id)
        closureSegments = emptyList()
        activeDetour = false
        val removedKind = activeRouteAdjustmentKind
        activeRouteAdjustmentKind = null
        sendTrackingAction(TrackingService.ACTION_UPDATE_NAVIGATION_ROUTE)
        if (detour.restoresRecordingRoute && recordingRouteStore.load(session.id) != null) {
            applyPersistedRecordingRoute(announce = false)
        } else {
            val originalReference = detour.originalRouteReference ?: session.routeReference ?: return
            importedTrackReference = null
            restoreActiveRoute(originalReference, RouteEntryMode.NEAREST_POINT)
        }
        binding.recordingUndoDetourButton.visibility = View.GONE
        showRouteStatus(
            getString(
                if (removedKind == RouteAdjustmentKind.REJOIN) {
                    R.string.route_rejoin_undo
                } else {
                    R.string.detour_removed
                },
            ),
            R.color.forest_900,
            INFO_BADGE_MILLIS,
        )
    }

    private fun renderSnapshot(snapshot: TrackingSnapshot) {
        if (snapshot.state == RecordingState.RECORDING) {
            stopVisibleLocationUpdates()
        } else if (
            lastRenderedRecordingState == RecordingState.RECORDING &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            startVisibleLocationUpdates()
        }
        renderRecordingPanelState(snapshot)
        binding.recordButton.setIconResource(R.drawable.ic_record)
        renderRouteDependentActions()
        val currentSpeed = snapshot.latestPoint
            ?.takeIf {
                snapshot.state == RecordingState.RECORDING &&
                    !snapshot.autoPaused &&
                    !snapshot.gpsGapActive &&
                    locationAgeMinutes(it) < STALE_LOCATION_MINUTES
            }
            ?.let(speedSmoother::update)
        renderStats(snapshot.stats, currentSpeed)
        renderLiveRecordingTimes(snapshot)
        snapshot.latestPoint?.let { point ->
            val previousPoint = latestLocatedPoint
            latestLocatedPoint = point
            if (
                previousPoint == null ||
                point.timeMillis != previousPoint.timeMillis ||
                point.latitude != previousPoint.latitude ||
                point.longitude != previousPoint.longitude
            ) {
            }
            if (snapshot.state == RecordingState.RECORDING) updateFollowCamera(point)
        }
        val observedPoint = snapshot.latestObservedPoint ?: snapshot.latestPoint
        observedPoint?.let(::renderGpsStatus)
        snapshot.errorMessage?.let { toast(it) }
        renderLocationStatus(observedPoint ?: latestLocatedPoint, snapshot.gpsGapActive)
        renderRouteProgress(snapshot.latestPoint ?: latestLocatedPoint)
        renderNavigationGuidance(snapshot.navigationGuidance)
        renderRouteRejoinGuidance(snapshot.latestPoint ?: latestLocatedPoint)
        offerFinishAtDestination(snapshot)
        if (snapshot.track !== lastRenderedLiveTrack) {
            lastRenderedLiveTrack = snapshot.track
            redrawTracks()
        } else {
            renderUserPosition()
        }
    }

    private fun renderRecordingPanelState(snapshot: TrackingSnapshot) {
        val state = snapshot.state
        val recordingActive = state == RecordingState.RECORDING || state == RecordingState.PAUSED
        val stateChanged = state != lastRenderedRecordingState
        if (stateChanged) {
            speedSmoother.reset()
            if (recordingActive && !recordingDrawerInitializedForSession) {
                recordingDrawerInitializedForSession = true
                binding.recordingCard.post {
                    setRecordingDrawerState(
                        if (state == RecordingState.PAUSED) BottomSheetBehavior.STATE_EXPANDED
                        else BottomSheetBehavior.STATE_COLLAPSED,
                    )
                }
            } else if (!recordingActive) {
                recordingDrawerInitializedForSession = false
                recordingDrawerState = BottomSheetBehavior.STATE_COLLAPSED
            }
            lastRenderedRecordingState = state
        }

        binding.actionsCard.visibility = if (recordingActive) View.GONE else View.VISIBLE
        binding.planningBar.visibility = if (recordingActive) View.GONE else View.VISIBLE
        binding.recordingCard.visibility = if (recordingActive) View.VISIBLE else View.GONE
        if (!recordingActive) {
            resetRecordingDrawerGeometry()
            binding.recordingDetourFab.visibility = View.GONE
            binding.root.post(::syncOverlayPositions)
            return
        }

        val paused = state == RecordingState.PAUSED
        binding.recordingStatusText.text = recordingStateLabel(
            when {
                paused -> R.string.recording_paused
                snapshot.autoPaused -> R.string.recording_auto_paused
                else -> R.string.recording_running
            },
            snapshot.activityType,
        )
        binding.recordingPausedBanner.visibility = if (paused || snapshot.autoPaused) View.VISIBLE else View.GONE
        binding.recordingPausedBanner.text = getString(
            if (paused) R.string.recording_paused else R.string.recording_auto_paused_banner,
        )
        binding.recordingPausedBanner.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (paused) R.color.warning else R.color.moss_300,
            ),
        )
        binding.recordingPausedBanner.setTextColor(
            ContextCompat.getColor(
                this,
                if (paused) R.color.white else R.color.forest_900,
            ),
        )
        binding.recordingExpandedGroup.visibility = View.VISIBLE
        binding.recordingPauseButton.visibility = if (paused) View.GONE else View.VISIBLE
        binding.recordingPausedActions.visibility = if (paused) View.VISIBLE else View.GONE
        binding.recordingDiscardButton.visibility = if (
            paused && RecordingRetentionPolicy.canDiscardInline(snapshot.stats.distanceMeters)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val detourAvailable = importedTrack != null
        binding.recordingDetourFab.visibility = if (detourAvailable) View.VISIBLE else View.GONE
        binding.recordingRouteButton.text = getString(
            if (importedTrack != null) R.string.edit_recording_route else R.string.set_recording_destination,
        )
        binding.recordingRejoinButton.visibility = if (
            detourAvailable && snapshot.confirmedOffRoute
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.recordingDetourActions.visibility = if (detourAvailable) View.VISIBLE else View.GONE
        binding.recordingUndoDetourButton.text = getString(
            if (activeRouteAdjustmentKind == RouteAdjustmentKind.REJOIN) {
                R.string.route_rejoin_undo
            } else {
                R.string.detour_undo
            },
        )
        binding.recordingUndoDetourButton.visibility = if (detourAvailable && activeDetour) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.recordingExpandButton.visibility = View.VISIBLE
        renderRecordingDrawerChrome()
        binding.root.post(::syncOverlayPositions)
    }

    private fun recordingStateLabel(stateLabelRes: Int, activityType: ActivityType?): String =
        activityType?.let { "${getString(stateLabelRes)} · ${getString(it.labelRes())}" }
            ?: getString(stateLabelRes)

    private fun confirmStopRecording() {
        val canDiscard = RecordingRetentionPolicy.canDiscardInline(latestSnapshot.stats.distanceMeters)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.finish_recording_title)
            .setMessage(R.string.finish_recording_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.finish_and_save) { _, _ ->
                sendTrackingAction(TrackingService.ACTION_STOP)
            }
        if (canDiscard) {
            builder.setNeutralButton(R.string.discard_recording) { _, _ ->
                discardRecordingAndClearRoute()
            }
        }
        val dialog = builder.show()
        if (canDiscard) {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setTextColor(
                ContextCompat.getColor(this, R.color.warning),
            )
        }
    }

    private fun confirmDiscardRecording() {
        if (!RecordingRetentionPolicy.canDiscardInline(latestSnapshot.stats.distanceMeters)) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_recording_title)
            .setMessage(R.string.discard_recording_message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.discard_recording) { _, _ ->
                discardRecordingAndClearRoute()
            }
            .setPositiveButton(R.string.finish_and_save) { _, _ ->
                sendTrackingAction(TrackingService.ACTION_STOP)
            }
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setTextColor(
            ContextCompat.getColor(this, R.color.warning),
        )
    }

    private fun renderStats(stats: TrackStats, currentSpeedMetersPerSecond: Double?) {
        binding.recordingDistanceText.text = String.format(
            displayLocale,
            "%.2f km",
            stats.distanceMeters / 1000.0,
        )
        binding.recordingMovingTimeText.text = formatDuration(stats.movingDurationMillis)
        binding.recordingCurrentSpeedText.text = currentSpeedMetersPerSecond
            ?.let(::formatSpeed)
            ?: getString(R.string.not_available)
        binding.recordingAscentText.text = getString(R.string.ascent_value, stats.ascentMeters.toInt())
        binding.recordingAverageSpeedText.text = stats.averageSpeedMetersPerSecond
            .takeIf { stats.movingDurationMillis > 0L }
            ?.let(::formatSpeed)
            ?: getString(R.string.not_available)
        binding.recordingAveragePaceText.text = stats.paceSecondsPerKilometer
            ?.let(::formatPace)
            ?: getString(R.string.not_available)
        binding.recordingTotalTimeText.text = formatDuration(stats.durationMillis)
        binding.recordingDescentText.text = getString(R.string.descent_value, stats.descentMeters.toInt())
        binding.recordingSlopeText.text = stats.currentSlopePercent?.let {
            getString(R.string.slope_percent_value, it)
        } ?: getString(R.string.not_available)
    }

    private fun renderLiveRecordingTimes(snapshot: TrackingSnapshot = latestSnapshot) {
        val durations = projectRecordingDurations(snapshot, SystemClock.elapsedRealtime())
        binding.recordingMovingTimeText.text = formatDuration(durations.movingMillis)
        binding.recordingTotalTimeText.text = formatDuration(durations.totalMillis)
    }

    private fun renderRouteStatus(point: TrackPoint?) {
        if (offlineDownloadInProgress) return
        val route = importedTrack
        if (point == null || route == null) {
            hideRouteStatus()
            return
        }
        val recordingActive = latestSnapshot.state == RecordingState.RECORDING ||
            latestSnapshot.state == RecordingState.PAUSED
        val monitoredDeviation = latestSnapshot.routeDeviationMeters.takeIf { recordingActive }
        val deviation = monitoredDeviation ?: GeoMath.distanceToTrackMeters(point, route) ?: return
        if (recordingActive && latestSnapshot.confirmedOffRoute) {
            showRouteStatus(getString(R.string.off_route, deviation.toInt()), R.color.warning)
        } else if (deviation <= OFF_ROUTE_THRESHOLD_METERS) {
            showRouteStatus(getString(R.string.on_route, deviation.toInt()), R.color.forest_900)
        } else if (recordingActive && monitoredDeviation != null) {
            showRouteStatus(getString(R.string.route_deviation_checking, deviation.toInt()), R.color.forest_900)
        } else {
            showRouteStatus(getString(R.string.off_route, deviation.toInt()), R.color.warning)
        }
    }

    private fun renderLocationStatus(point: TrackPoint?, gpsGapActive: Boolean) {
        if (offlineDownloadInProgress) return
        val accuracy = point?.accuracyMeters
        val inaccurate = accuracy != null && accuracy > GpsQuality.RELIABLE_ACCURACY_METERS
        val warningConfirmed = point?.timeMillis?.let { sampleMillis ->
            gpsQualityWarningMonitor.update(inaccurate, sampleMillis)
        } ?: false
        when {
            gpsGapActive -> showRouteStatus(getString(R.string.gps_gap_recording), R.color.warning)
            point != null && locationAgeMinutes(point) >= STALE_LOCATION_MINUTES -> {
                showRouteStatus(
                    getString(R.string.gps_position_stale, locationAgeMinutes(point)),
                    R.color.warning,
                )
            }
            inaccurate && warningConfirmed -> {
                showRouteStatus(
                    getString(R.string.gps_position_uncertain, accuracy.toInt()),
                    R.color.warning,
                )
            }
            inaccurate -> Unit
            else -> renderRouteStatus(point)
        }
    }

    private fun renderGpsStatus(point: TrackPoint) {
        val accuracy = point.accuracyMeters
        val ageMinutes = locationAgeMinutes(point)
        binding.recordingGpsText.text = when {
            ageMinutes >= STALE_LOCATION_MINUTES -> getString(R.string.gps_age_short, ageMinutes)
            accuracy == null -> getString(R.string.gps_active_short)
            else -> getString(R.string.gps_accuracy_short, accuracy.toInt())
        }
    }

    private fun locationAgeMinutes(point: TrackPoint): Int {
        val timestamp = point.timeMillis ?: return 0
        return ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 60_000L)
            .toInt()
    }

    private fun showRouteStatus(message: CharSequence, colorRes: Int, autoHideMillis: Long? = null) {
        binding.routeStatusText.removeCallbacks(restoreRouteStatusRunnable)
        currentRouteStatusMessage = message
        currentRouteStatusColorRes = colorRes
        renderRouteStatusPresentation()
        autoHideMillis?.let { binding.routeStatusText.postDelayed(restoreRouteStatusRunnable, it) }
    }

    private fun hideRouteStatus() {
        binding.routeStatusText.removeCallbacks(restoreRouteStatusRunnable)
        currentRouteStatusMessage = null
        renderRouteStatusPresentation()
    }

    /**
     * While the navigation banner is up the status belongs inside it; two stacked banners would
     * otherwise cover the map. Without the banner the status keeps its own pill.
     */
    private fun renderRouteStatusPresentation() {
        val insideBanner = binding.navigationManeuverBanner.visibility == View.VISIBLE
        val carrier = if (insideBanner) binding.navigationStatusText else binding.routeStatusText
        val unused = if (insideBanner) binding.routeStatusText else binding.navigationStatusText
        unused.visibility = View.GONE
        val message = currentRouteStatusMessage
        if (message == null) {
            carrier.visibility = View.GONE
            return
        }
        carrier.text = message
        carrier.setTextColor(ContextCompat.getColor(this, routeStatusColorRes(insideBanner)))
        carrier.visibility = View.VISIBLE
    }

    /** The dark banner needs a lighter palette than the sand coloured pill. */
    private fun routeStatusColorRes(insideBanner: Boolean): Int = when {
        !insideBanner -> currentRouteStatusColorRes
        currentRouteStatusColorRes == R.color.warning -> R.color.navigation_status_warning
        else -> R.color.navigation_status
    }

    private fun syncOverlayPositions() {
        val recordingActive = latestSnapshot.state == RecordingState.RECORDING ||
            latestSnapshot.state == RecordingState.PAUSED
        val overlayTop = if (recordingActive) {
            recordingSheetTopInRoot()
        } else {
            binding.actionsCard.y.roundToInt()
        }
        if (binding.root.height > 0 && overlayTop > 0 && binding.centerButton.height > 0) {
            val minimumTop = binding.root.paddingTop + dp(12)
            val centerTop = (overlayTop - binding.centerButton.height - dp(16))
                .coerceAtLeast(minimumTop)
            binding.centerButton.y = centerTop.toFloat()
            var stackTop = centerTop
            if (binding.mapSettingsFab.height > 0) {
                stackTop = (stackTop - binding.mapSettingsFab.height - dp(8))
                    .coerceAtLeast(minimumTop)
                binding.mapSettingsFab.y = stackTop.toFloat()
            }
            if (binding.recordingDetourFab.height > 0) {
                binding.recordingDetourFab.y = (
                    stackTop - binding.recordingDetourFab.height - dp(8)
                ).coerceAtLeast(minimumTop).toFloat()
            }
        }

        val layoutParams = binding.routeStatusText.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        val density = resources.displayMetrics.density
        val topMargin = if (binding.planningBar.visibility == View.VISIBLE) {
            binding.planningBar.height + (20 * density).roundToInt()
        } else {
            (12 * density).roundToInt()
        }
        if (layoutParams.topMargin != topMargin) {
            layoutParams.topMargin = topMargin
            binding.routeStatusText.layoutParams = layoutParams
        }
    }

    private fun recordingSheetTopInRoot(): Int =
        (binding.recordingSheetHost.y + binding.recordingCard.y).roundToInt()

    private fun scheduleOverlayPositionSync() {
        binding.root.doOnPreDraw { syncOverlayPositions() }
    }

    private fun navigationBarHeightFallback(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun importGpx(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val parsedTrack = contentResolver.openInputStream(uri)?.use {
                        GpxCodec.parse(it, uri.lastPathSegment ?: getString(R.string.imported_route_fallback))
                    } ?: error(getString(R.string.file_open_error))
                    val track = runCatching { ElevationEnricher().enrichIfMissing(parsedTrack) }
                        .onFailure { Log.w(LOG_TAG, "Elevation enrichment failed", it) }
                        .getOrDefault(parsedTrack)
                    val stored = trackStore.saveImportedTrack(track)
                    track to stored.reference
                }
            }
            result.onSuccess { (track, reference) ->
                displayTrack(track, reference, askForOfflineDownload = false)
                askToDownloadOfflineMap(track) { openTourDetails(reference) }
            }.onFailure {
                Log.e(LOG_TAG, "GPX import failed for $uri", it)
                toast(getString(R.string.gpx_load_error, it.localizedMessage ?: getString(R.string.unknown_error)))
            }
        }
    }

    private fun openStoredTour(reference: String, askForOfflineDownload: Boolean = false) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { trackStore.loadStoredTrack(reference) }
            }
            result.onSuccess { displayTrack(it, reference, askForOfflineDownload = askForOfflineDownload) }
                .onFailure {
                    toast(getString(R.string.tour_open_error, it.localizedMessage ?: getString(R.string.unknown_error)))
                }
        }
    }

    private fun displayTrack(
        track: GpxTrack,
        reference: String?,
        askForOfflineDownload: Boolean,
        announce: Boolean = true,
        frameTrack: Boolean = true,
        displayedRoute: GpxTrack = track,
        detourOverlay: GpxTrack? = null,
    ) {
        initialRegionFramingComplete = true
        importedTrack = track
        displayedRouteTrack = displayedRoute
        detourOverlayTrack = detourOverlay
        offlineMapIdentityTrack = track
        importedTrackReference = reference
        routeProgressTracker = RouteProgressTracker(track)
        routeRejoinAdvisor = RouteRejoinAdvisor(track)
        renderRouteDependentActions()
        if (announce) {
            showRouteStatus(
                getString(R.string.route_points_loaded, track.points.size),
                R.color.forest_900,
                ROUTE_LOADED_BADGE_MILLIS,
            )
        }
        renderRouteProgress(latestSnapshot.latestPoint ?: latestLocatedPoint)
        redrawTracks()
        if (frameTrack) fitTrack(track)
        if (askForOfflineDownload) askToDownloadOfflineMap(track)
    }

    private fun askToDownloadOfflineMap(track: GpxTrack, onDecision: () -> Unit = {}) {
        val plan = runCatching { OfflineMapPlanner.plan(track) }.getOrElse {
            showRouteStatus(
                getString(R.string.offline_map_error, it.localizedMessage ?: getString(R.string.unknown_error)),
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
        updateLineSource(style, ROUTE_SOURCE, displayedRouteTrack ?: importedTrack)
        updateLineSource(style, CLOSURE_SOURCE, closureSegments)
        updateLineSource(style, DETOUR_SOURCE, detourOverlayTrack)
        updateRouteEndpointMarkers(displayedRouteTrack ?: importedTrack)
        updateLiveTrackSources(style, latestSnapshot.track)
        renderUserPosition(style)
    }

    private fun renderUserPosition() {
        renderUserPosition(mapStyle ?: return)
    }

    private fun renderUserPosition(style: Style) {
        val pointSource = style.getSourceAs<GeoJsonSource>(POSITION_SOURCE) ?: return
        val directionSource = style.getSourceAs<GeoJsonSource>(POSITION_DIRECTION_SOURCE) ?: return
        val point = displayedPosition()
        if (point == null) {
            pointSource.setGeoJson(EMPTY_FEATURE_COLLECTION)
            directionSource.setGeoJson(EMPTY_FEATURE_COLLECTION)
        } else {
            val positionFeature = FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)),
            )
            pointSource.setGeoJson(positionFeature)
            val heading = displayHeading(point)
            if (heading == null || locationAgeMinutes(point) >= STALE_LOCATION_MINUTES) {
                directionSource.setGeoJson(EMPTY_FEATURE_COLLECTION)
            } else {
                directionSource.setGeoJson(positionFeature)
                style.getLayerAs<SymbolLayer>(POSITION_DIRECTION_LAYER)?.setProperties(iconRotate(heading))
            }
        }
        style.getLayerAs<CircleLayer>(POSITION_HALO_LAYER)?.setProperties(
            circleColor(
                if ((point?.accuracyMeters ?: 0f) > GpsQuality.RELIABLE_ACCURACY_METERS) {
                    Color.parseColor("#F26B38")
                } else {
                    Color.parseColor("#1677FF")
                },
            ),
            circleRadius(
                if ((point?.accuracyMeters ?: 0f) > GpsQuality.RELIABLE_ACCURACY_METERS) 20f else 16f,
            ),
        )
    }

    private fun displayHeading(point: TrackPoint): Float? = latestCompassHeadingDegrees
        ?.let(HeadingSmoother::normalize)
        // A phone without a rotation sensor can still show a coarse movement direction.
        ?: point.bearingDegrees?.takeIf {
            (point.speedMetersPerSecond ?: 0f) >= MIN_DIRECTION_SPEED_METERS_PER_SECOND
        }

    private fun displayedPosition(): TrackPoint? =
        listOfNotNull(latestSnapshot.latestPoint, latestLocatedPoint)
            .maxByOrNull { it.timeMillis ?: Long.MIN_VALUE }

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

    private fun createPositionDirectionIcon(): Bitmap {
        val density = resources.displayMetrics.density
        val size = (42 * density).toInt()
        val center = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val arrow = Path().apply {
            moveTo(center, 1.5f * density)
            lineTo(center + 8f * density, center + 6f * density)
            lineTo(center, center + 2f * density)
            lineTo(center - 8f * density, center + 6f * density)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 5f * density
            color = Color.WHITE
        }
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#1677FF")
        canvas.drawPath(arrow, paint)
        return bitmap
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
        updateLineSource(style, sourceId, track?.segments.orEmpty())
    }

    private fun updateLineSource(style: Style, sourceId: String, segments: List<List<TrackPoint>>) {
        val source = style.getSourceAs<GeoJsonSource>(sourceId) ?: return
        val features = segments.filter { it.size >= 2 }.map { segment ->
            Feature.fromGeometry(
                LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun requestRecordingStart() {
        gpsQualityWarningMonitor.reset()
        val types = ActivityType.entries.toTypedArray()
        val preferredType = importedTrack?.activityType ?: activityPreferences.defaultType
        var selectedIndex = types.indexOf(preferredType).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.activity_type_title)
            .setSingleChoiceItems(
                types.map { getString(it.labelRes()) }.toTypedArray(),
                selectedIndex,
            ) { _, which -> selectedIndex = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                selectedActivityType = types[selectedIndex]
                activityPreferences.defaultType = selectedActivityType
                showCombinedStartCheck()
            }
            .show()
    }

    private fun showCombinedStartCheck() {
        val route = importedTrack
        if (route == null) {
            showCombinedStartCheckDialog(null, null, null, null)
            return
        }
        val assessment = reliableStartPosition()?.let { RouteStartAssessor.assess(route, it) }
        val initialMode = assessment?.recommendedEntryMode ?: RouteEntryMode.OFFICIAL_START
        offlineMapDownloader.status(offlineMapIdentityTrack ?: route) { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showCombinedStartCheckDialog(route, status, assessment, initialMode)
            }
        }
    }

    private fun showCombinedStartCheckDialog(
        route: GpxTrack?,
        offlineStatus: OfflineMapStatus?,
        assessment: RouteStartAssessment?,
        initialMode: RouteEntryMode?,
    ) {
        val dialogBinding = DialogRecordingStartCheckBinding.inflate(layoutInflater)
        var routeEntryMode = initialMode
        if (route != null) {
            dialogBinding.routeEntryContainer.visibility = View.VISIBLE
            dialogBinding.routeEntryMessage.text = routeEntryMessage(assessment)
            dialogBinding.officialStartButton.text = routeEntryLabel(
                RouteEntryMode.OFFICIAL_START,
                assessment,
            )
            dialogBinding.nearestPointButton.text = routeEntryLabel(
                RouteEntryMode.NEAREST_POINT,
                assessment,
            )
            dialogBinding.routeEntryGroup.check(
                if (initialMode == RouteEntryMode.NEAREST_POINT) {
                    R.id.nearestPointButton
                } else {
                    R.id.officialStartButton
                },
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_check_title)
            .setView(dialogBinding.root)
            .create()

        fun renderStartCheck() {
            val lines = buildStartCheckLines(route, offlineStatus, routeEntryMode)
            val hasWarnings = lines.any(StartCheckLine::warning)
            dialogBinding.startCheckText.text = lines.joinToString("\n\n") { line ->
                "${if (line.warning) "⚠" else "✓"}  ${line.text}"
            }
            dialogBinding.confirmStartButton.setText(
                if (hasWarnings) R.string.start_despite_warnings else R.string.start_recording,
            )
        }

        dialogBinding.cancelStartButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.confirmStartButton.setOnClickListener {
            beginRecordingStart(routeEntryMode)
            dialog.dismiss()
        }
        dialogBinding.routeEntryGroup.setOnCheckedChangeListener { _, checkedId ->
            routeEntryMode = if (checkedId == R.id.nearestPointButton) {
                RouteEntryMode.NEAREST_POINT
            } else {
                RouteEntryMode.OFFICIAL_START
            }
            renderStartCheck()
        }
        dialog.setOnShowListener {
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val safeWidth = screenWidth - (START_DIALOG_TOTAL_MARGIN_DP * density).roundToInt()
            val maximumWidth = (START_DIALOG_MAX_WIDTH_DP * density).roundToInt()
            dialog.window?.setLayout(
                minOf(safeWidth, maximumWidth),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        renderStartCheck()
        dialog.show()
    }

    private fun routeEntryLabel(
        mode: RouteEntryMode,
        assessment: RouteStartAssessment?,
    ): String {
        val label = getString(
            if (mode == RouteEntryMode.OFFICIAL_START) {
                R.string.route_entry_official_start
            } else {
                R.string.route_entry_nearest_point
            },
        )
        return if (mode == assessment?.recommendedEntryMode) {
            getString(R.string.route_entry_recommended, label)
        } else {
            label
        }
    }

    private fun reliableStartPosition(): TrackPoint? = displayedPosition()?.takeIf { position ->
        LocationManagerCompat.isLocationEnabled(locationManager) &&
            hasLocationPermission() &&
            locationAgeMinutes(position) < STALE_LOCATION_MINUTES &&
            (position.accuracyMeters ?: Float.MAX_VALUE) <= GpsQuality.RELIABLE_ACCURACY_METERS
    }

    private fun routeEntryMessage(assessment: RouteStartAssessment?): String = when (assessment?.situation) {
        RouteStartSituation.AT_START -> getString(
            R.string.route_entry_at_start,
            formatRemainingDistance(assessment.distanceToStartMeters),
        )
        RouteStartSituation.ON_ROUTE -> getString(
            R.string.route_entry_on_route,
            formatRouteKilometer(assessment.distanceAlongRouteMeters),
        )
        RouteStartSituation.NEAR_ROUTE -> getString(
            R.string.route_entry_near_route,
            formatRemainingDistance(assessment.distanceToRouteMeters),
            formatRemainingDistance(assessment.distanceToStartMeters),
        )
        RouteStartSituation.AWAY_FROM_ROUTE -> getString(
            R.string.route_entry_away_from_route,
            formatRemainingDistance(assessment.distanceToRouteMeters),
            formatRemainingDistance(assessment.distanceToStartMeters),
        )
        null -> getString(R.string.route_entry_location_unknown)
    }

    private fun formatRouteKilometer(distanceMeters: Double?): String = distanceMeters
        ?.let { String.format(displayLocale, "%.1f", it / 1_000.0) }
        ?: getString(R.string.not_available)

    private fun beginRecordingStart(routeEntryMode: RouteEntryMode?) {
        importedTrack?.let { route ->
            routeProgressTracker = RouteProgressTracker(
                route,
                routeEntryMode ?: RouteEntryMode.OFFICIAL_START,
            )
            renderRouteProgress(latestSnapshot.latestPoint ?: latestLocatedPoint)
        }
        startRecordingWithPermissions()
    }

    private fun buildStartCheckLines(
        route: GpxTrack?,
        offlineStatus: OfflineMapStatus?,
        routeEntryMode: RouteEntryMode?,
    ): List<StartCheckLine> = buildList {
        add(
            StartCheckLine(
                getString(R.string.start_check_activity, getString(selectedActivityType.labelRes())),
                false,
            ),
        )
        val locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
        val position = displayedPosition()
        when {
            !locationEnabled -> add(StartCheckLine(getString(R.string.start_check_gps_disabled), true))
            !hasLocationPermission() -> add(StartCheckLine(getString(R.string.start_check_gps_permission), true))
            position == null -> add(StartCheckLine(getString(R.string.start_check_gps_waiting), true))
            locationAgeMinutes(position) >= STALE_LOCATION_MINUTES -> add(
                StartCheckLine(
                    getString(R.string.start_check_gps_stale, locationAgeMinutes(position)),
                    true,
                ),
            )
            (position.accuracyMeters ?: Float.MAX_VALUE) > GpsQuality.RELIABLE_ACCURACY_METERS -> add(
                StartCheckLine(
                    getString(
                        R.string.start_check_gps_inaccurate,
                        (position.accuracyMeters ?: 0f).roundToInt(),
                    ),
                    true,
                ),
            )
            else -> add(
                StartCheckLine(
                    getString(
                        R.string.start_check_gps_ready,
                        (position.accuracyMeters ?: 0f).roundToInt(),
                    ),
                    false,
                ),
            )
        }

        val battery = batteryState()
        if (battery == null) {
            add(StartCheckLine(getString(R.string.start_check_battery_unknown), true))
        } else {
            add(
                StartCheckLine(
                    getString(
                        if (battery.charging) R.string.start_check_battery_charging else R.string.start_check_battery,
                        battery.percent,
                    ),
                    warning = battery.percent <= LOW_BATTERY_WARNING_PERCENT && !battery.charging,
                ),
            )
        }

        if (route == null) {
            add(StartCheckLine(getString(R.string.start_check_no_route), false))
            return@buildList
        }
        when (offlineStatus?.availability) {
            OfflineMapAvailability.DOWNLOADED -> add(
                StartCheckLine(
                    getString(
                        R.string.start_check_offline_ready,
                        Formatter.formatShortFileSize(this@MainActivity, offlineStatus.downloadedBytes),
                    ),
                    false,
                ),
            )
            OfflineMapAvailability.PARTIAL -> add(
                StartCheckLine(getString(R.string.start_check_offline_partial), true),
            )
            OfflineMapAvailability.NOT_DOWNLOADED -> add(
                StartCheckLine(getString(R.string.start_check_offline_missing), true),
            )
            OfflineMapAvailability.ERROR, OfflineMapAvailability.CHECKING, null -> add(
                StartCheckLine(getString(R.string.start_check_offline_unknown), true),
            )
        }

        val startAssessment = reliableStartPosition()?.let { RouteStartAssessor.assess(route, it) }
        if (startAssessment == null) {
            add(StartCheckLine(getString(R.string.start_check_route_position_unknown), true))
        } else {
            add(
                StartCheckLine(
                    text = when (startAssessment.situation) {
                        RouteStartSituation.AT_START -> getString(
                            R.string.start_check_at_start,
                            formatRemainingDistance(startAssessment.distanceToStartMeters),
                        )
                        RouteStartSituation.ON_ROUTE -> getString(
                            R.string.start_check_on_route,
                            formatRouteKilometer(startAssessment.distanceAlongRouteMeters),
                            formatRemainingDistance(startAssessment.distanceToStartMeters),
                        )
                        RouteStartSituation.NEAR_ROUTE -> getString(
                            R.string.start_check_near_route,
                            formatRemainingDistance(startAssessment.distanceToRouteMeters),
                            formatRemainingDistance(startAssessment.distanceToStartMeters),
                        )
                        RouteStartSituation.AWAY_FROM_ROUTE -> getString(
                            R.string.start_check_away_from_route,
                            formatRemainingDistance(startAssessment.distanceToRouteMeters),
                            formatRemainingDistance(startAssessment.distanceToStartMeters),
                        )
                    },
                    warning = startAssessment.situation == RouteStartSituation.NEAR_ROUTE ||
                        startAssessment.situation == RouteStartSituation.AWAY_FROM_ROUTE,
                ),
            )
        }
        add(
            StartCheckLine(
                text = when (routeEntryMode) {
                    RouteEntryMode.NEAREST_POINT -> getString(R.string.start_check_entry_nearest)
                    else -> getString(R.string.start_check_entry_official)
                },
                warning = routeEntryMode == RouteEntryMode.OFFICIAL_START &&
                    startAssessment != null &&
                    startAssessment.situation != RouteStartSituation.AT_START,
            ),
        )

        if (selectedActivityType == ActivityType.HIKING) {
            val insights = TourInsightsAnalyzer.analyze(route)
            val fitness = fitnessPreferences.level
            val forecast = TourForecaster.forecast(insights.stats, insights.elevationProfile, fitness)
            val forecastText = forecast?.let { formatDuration(it.totalDurationMillis) }
                ?: getString(R.string.not_available)
            add(
                StartCheckLine(
                    getString(
                        R.string.start_check_forecast,
                        fitnessLabel(fitness),
                        forecastText,
                    ),
                    false,
                ),
            )
        } else {
            add(
                StartCheckLine(
                    getString(
                        R.string.start_check_forecast_not_calibrated,
                        getString(selectedActivityType.labelRes()),
                    ),
                    false,
                ),
            )
        }
    }

    private fun startRecordingWithPermissions() {
        if (hasLocationPermission()) {
            focusOnUser()
            requestRecordingNotificationPermission()
            sendTrackingAction(TrackingService.ACTION_START, startForeground = true)
            return
        }
        pendingRecordingStart = true
        pendingCenterRequest = true
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestRecordingNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showRecordingNotificationSettingsDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recording_notification_disabled_title)
            .setMessage(R.string.recording_notification_disabled_message)
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(R.string.open_notification_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            .show()
    }

    private fun batteryState(): BatteryState? {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val chargingStatus = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return BatteryState(
            percent = (level * 100f / scale).roundToInt(),
            charging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                chargingStatus == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun fitnessLabel(level: HikingFitnessLevel): String = getString(
        when (level) {
            HikingFitnessLevel.LEISURELY -> R.string.fitness_leisurely
            HikingFitnessLevel.AVERAGE -> R.string.fitness_average
            HikingFitnessLevel.FIT -> R.string.fitness_fit
            HikingFitnessLevel.SPORTY -> R.string.fitness_sporty
        },
    )

    private fun sendTrackingAction(action: String, startForeground: Boolean = false) {
        val intent = Intent(this, TrackingService::class.java)
            .setAction(action)
            .apply {
                if (action == TrackingService.ACTION_START) {
                    putExtra(TrackingService.EXTRA_ACTIVITY_TYPE, selectedActivityType.name)
                    importedTrack?.name?.let { putExtra(TrackingService.EXTRA_ROUTE_NAME, it) }
                    importedTrackReference?.let {
                        putExtra(TrackingService.EXTRA_ROUTE_REFERENCE, it)
                    }
                }
            }
        if (startForeground) ContextCompat.startForegroundService(this, intent) else startService(intent)
    }

    private fun discardRecordingAndClearRoute() {
        clearLoadedRoute()
        sendTrackingAction(TrackingService.ACTION_DISCARD)
    }

    private fun clearLoadedRoute() {
        importedTrack = null
        displayedRouteTrack = null
        detourOverlayTrack = null
        closureSegments = emptyList()
        offlineMapIdentityTrack = null
        importedTrackReference = null
        activeDetour = false
        activeRouteAdjustmentKind = null
        routeProgressTracker = null
        routeRejoinAdvisor = null
        binding.recordingRouteProgressGroup.visibility = View.GONE
        binding.routeRejoinBanner.visibility = View.GONE
        renderRouteDependentActions()
        hideRouteStatus()
        redrawTracks()
    }

    private fun showMoreMenu() {
        PopupMenu(this, binding.moreButton).apply {
            if (importedTrack != null) {
                if (importedTrackReference?.startsWith("imported:") == true) {
                    menu.add(0, MENU_EDIT_ROUTE, 1, getString(R.string.edit_tour))
                }
                menu.add(0, MENU_FIT_ROUTE, 2, getString(R.string.fit_route))
                menu.add(0, MENU_REVERSE_ROUTE, 3, getString(R.string.reverse_route))
                menu.add(0, MENU_CLEAR_ROUTE, 4, getString(R.string.hide_route))
            }
            if (menu.size() == 0) menu.add(getString(R.string.no_more_actions)).isEnabled = false
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT_ROUTE -> {
                        val reference = importedTrackReference ?: return@setOnMenuItemClickListener false
                        tourEditorLauncher.launch(
                            Intent(this@MainActivity, RoutePlannerActivity::class.java)
                                .putExtra(RoutePlannerActivity.EXTRA_EDIT_TOUR_REFERENCE, reference),
                        )
                        true
                    }
                    MENU_CLEAR_ROUTE -> {
                        clearLoadedRoute()
                        true
                    }
                    MENU_FIT_ROUTE -> {
                        importedTrack?.let(::fitTrack)
                        true
                    }
                    MENU_REVERSE_ROUTE -> {
                        importedTrack = importedTrack?.reversed()
                        displayedRouteTrack = importedTrack
                        detourOverlayTrack = null
                        closureSegments = emptyList()
                        routeProgressTracker = importedTrack?.let(::RouteProgressTracker)
                        routeRejoinAdvisor = importedTrack?.let(::RouteRejoinAdvisor)
                        renderRouteProgress(latestSnapshot.latestPoint ?: latestLocatedPoint)
                        redrawTracks()
                        showRouteStatus(
                            getString(R.string.route_reversed),
                            R.color.forest_900,
                            INFO_BADGE_MILLIS,
                        )
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showMapSettingsMenu() {
        PopupMenu(this, binding.mapSettingsFab).apply {
            menu.add(0, MENU_VOICE_GUIDANCE, 1, getString(R.string.voice_guidance)).apply {
                isCheckable = true
                isChecked = navigationPreferences.voiceGuidanceEnabled
            }
            menu.setGroupCheckable(0, true, false)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_VOICE_GUIDANCE -> {
                        val enabled = !navigationPreferences.voiceGuidanceEnabled
                        navigationPreferences.voiceGuidanceEnabled = enabled
                        item.isChecked = enabled
                        showRouteStatus(
                            getString(
                                if (enabled) R.string.voice_guidance_on else R.string.voice_guidance_off,
                            ),
                            R.color.forest_900,
                            INFO_BADGE_MILLIS,
                        )
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun renderRouteDependentActions() {
        binding.moreButton.visibility = if (importedTrack != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        renderRecordButtonLabel()
    }

    private fun renderRecordButtonLabel() {
        binding.recordButton.text = getString(
            if (importedTrack != null) R.string.start_tour else R.string.record,
        )
    }

    private fun fitTrack(track: GpxTrack) {
        initialRegionFramingComplete = true
        // The framed route must stay in view; the next fix would otherwise pull the camera away.
        followLocation = false
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
            val recordingActive = latestSnapshot.state == RecordingState.RECORDING ||
                latestSnapshot.state == RecordingState.PAUSED
            val topPadding = horizontalPadding
            val bottomOverlayTop = if (recordingActive) {
                recordingSheetTopInRoot()
            } else {
                binding.actionsCard.top
            }
            val bottomPadding = (
                binding.mapView.bottom - bottomOverlayTop + (16 * density).toInt()
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

    /**
     * Keeps the camera on the hiker while following. The zoom stays untouched, so a manually
     * chosen zoom level survives every position update.
     */
    private fun updateFollowCamera(point: TrackPoint) {
        if (!followLocation) return
        val readyMap = map ?: return
        val camera = readyMap.cameraPosition
        val target = LatLng(point.latitude, point.longitude)
        val movedMeters = cameraTargetDistanceMeters(camera, target)
        val heading = steeringHeadingDegrees(point)
            ?.takeIf { mapOrientationMode == MapOrientationMode.HEADING_UP }
        if (heading != null) {
            val turnedDegrees = HeadingSmoother.angularDistance(camera.bearing.toFloat(), heading)
            val now = SystemClock.elapsedRealtime()
            val worthTurning = turnedDegrees > HEADING_UP_MIN_TURN_DEGREES ||
                movedMeters > FOLLOW_RECENTER_METERS
            if (!worthTurning || now - lastHeadingUpEaseMillis < HEADING_UP_MIN_INTERVAL_MILLIS) return
            lastHeadingUpEaseMillis = now
            readyMap.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(camera)
                        .target(target)
                        .bearing(heading.toDouble())
                        .build(),
                ),
                FOLLOW_CAMERA_MILLIS,
            )
            return
        }
        // Without this guard every fix restarts the animation and the map never settles.
        if (movedMeters <= FOLLOW_RECENTER_METERS) return
        readyMap.easeCamera(CameraUpdateFactory.newLatLng(target), FOLLOW_CAMERA_MILLIS)
    }

    /**
     * The first tap straightens a map the hiker turned by hand, the next one switches between
     * north-up and heading-up.
     */
    private fun switchMapOrientation() {
        val readyMap = map ?: return
        val action = MapOrientationController.nextAction(
            mode = mapOrientationMode,
            bearingDegrees = readyMap.cameraPosition.bearing,
            following = followLocation,
        )
        mapOrientationMode = MapOrientationController.modeAfter(action)
        when (action) {
            MapOrientationAction.START_HEADING_UP -> {
                followLocation = true
                initialRegionFramingComplete = true
                lastHeadingUpEaseMillis = 0L
                displayedPosition()?.let(::updateFollowCamera)
            }
            MapOrientationAction.ALIGN_NORTH, MapOrientationAction.STOP_HEADING_UP -> {
                readyMap.animateCamera(CameraUpdateFactory.bearingTo(0.0), COMPASS_ALIGN_MILLIS)
            }
        }
        renderCompassFab()
    }

    /** Keeps the needle pointing at true north and shows whether heading-up is steering. */
    private fun renderCompassFab() {
        if (!::binding.isInitialized) return
        binding.compassFab.rotation = -(map?.cameraPosition?.bearing ?: 0.0).toFloat()
        val headingUp = mapOrientationMode == MapOrientationMode.HEADING_UP && followLocation
        if (renderedCompassHeadingUp == headingUp) return
        renderedCompassHeadingUp = headingUp
        binding.compassFab.setImageResource(
            if (headingUp) R.drawable.ic_compass_needle_active else R.drawable.ic_compass_needle,
        )
        binding.compassFab.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (headingUp) R.color.forest_700 else R.color.sand_50,
        )
    }

    /**
     * Steers the camera in heading-up mode. The satellite course is the calmer source while
     * walking; standing still it would be noise, so the compass takes over there.
     */
    private fun steeringHeadingDegrees(point: TrackPoint): Float? {
        val moving = (point.speedMetersPerSecond ?: 0f) >= HEADING_UP_MIN_SPEED_METERS_PER_SECOND
        val course = point.bearingDegrees?.takeIf { moving }
        return (course ?: latestCompassHeadingDegrees)?.let(HeadingSmoother::normalize)
    }

    private fun cameraTargetDistanceMeters(camera: CameraPosition, target: LatLng): Double =
        distanceBetweenMeters(camera.target, target)

    private fun distanceBetweenMeters(from: LatLng?, to: LatLng): Double =
        if (from == null) Double.MAX_VALUE else from.distanceTo(to)

    /** Returns true when this call framed the initial region. */
    private fun frameInitialRegionIfNeeded(point: TrackPoint?): Boolean {
        if (
            initialRegionFramingComplete ||
            point == null ||
            importedTrack != null ||
            latestSnapshot.state == RecordingState.RECORDING ||
            latestSnapshot.state == RecordingState.PAUSED ||
            map == null
        ) {
            return false
        }
        initialRegionFramingComplete = true
        centerOn(point, INITIAL_REGION_ZOOM)
        return true
    }

    private fun requestCenterOnUser() {
        if (hasLocationPermission()) {
            focusOnUser()
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

    private fun focusOnUser() {
        initialRegionFramingComplete = true
        followLocation = true
        renderCompassFab()
        displayedPosition()?.let { centerOn(it, USER_FOCUS_ZOOM) }
        startVisibleLocationUpdates()
        locateUser(centerAfterFix = true)
    }

    @SuppressLint("MissingPermission")
    private fun locateUser(centerAfterFix: Boolean) {
        val providers = enabledLocationProviders()
        if (providers.isEmpty()) {
            if (centerAfterFix) toast(getString(R.string.location_services_disabled))
            return
        }

        val lastKnown = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (lastKnown != null) showLocatedPosition(lastKnown, centerAfterFix)
        else {
            showRouteStatus(getString(R.string.gps_locating), R.color.forest_900)
        }

        locationRequestSignals.forEach(CancellationSignal::cancel)
        locationRequestSignals.clear()
        providers.forEach { provider ->
            val signal = CancellationSignal()
            locationRequestSignals += signal
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                signal,
                ContextCompat.getMainExecutor(this),
            ) { location ->
                if (location != null) {
                    showLocatedPosition(location, centerAfterFix)
                } else if (latestLocatedPoint == null && latestSnapshot.latestPoint == null) {
                    showRouteStatus(getString(R.string.gps_no_fix), R.color.warning)
                }
            }
        }
    }

    private fun showLocatedPosition(location: Location, centerAfterFix: Boolean) {
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            elevationMeters = location.altitude.takeIf { location.hasAltitude() },
            timeMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
        )
        val existing = displayedPosition()
        if (existing != null && !isBetterLocation(point, existing)) return
        latestLocatedPoint = point
        renderGpsStatus(point)
        renderLocationStatus(point, latestSnapshot.gpsGapActive)
        renderRouteProgress(point)
        renderUserPosition()
        if (centerAfterFix) {
            initialRegionFramingComplete = true
            centerOn(point, USER_FOCUS_ZOOM)
        } else if (!frameInitialRegionIfNeeded(point)) {
            updateFollowCamera(point)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVisibleLocationUpdates() {
        if (
            visibleLocationUpdatesActive ||
            latestSnapshot.state == RecordingState.RECORDING ||
            !hasLocationPermission()
        ) return
        val providers = enabledLocationProviders()
        if (providers.isEmpty()) return
        providers.forEach { provider ->
            runCatching {
                val minTime = if (provider == LocationManager.GPS_PROVIDER) 2_000L else 6_000L
                val minDistance = if (provider == LocationManager.GPS_PROVIDER) 1f else 4f
                locationManager.requestLocationUpdates(provider, minTime, minDistance, this)
            }
        }
        visibleLocationUpdatesActive = true
    }

    private fun enabledLocationProviders(): List<String> {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return buildList {
            if (fineGranted) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun stopVisibleLocationUpdates() {
        if (!visibleLocationUpdatesActive) return
        runCatching { locationManager.removeUpdates(this) }
        visibleLocationUpdatesActive = false
    }

    override fun onLocationChanged(location: Location) {
        showLocatedPosition(location, centerAfterFix = false)
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

    private fun formatSpeed(metersPerSecond: Double): String =
        String.format(displayLocale, "%.1f", metersPerSecond * 3.6)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun renderRouteProgress(point: TrackPoint?) {
        val route = importedTrack
        val tracker = routeProgressTracker
        if (route == null) {
            renderRecordingElevationProfile(
                source = latestSnapshot.track,
                progressDistanceMeters = null,
                plannedRoute = false,
            )
            binding.recordingRouteProgressGroup.visibility = View.GONE
            return
        }
        if (tracker == null) {
            renderRecordingElevationProfile(
                source = route,
                progressDistanceMeters = null,
                plannedRoute = true,
            )
            binding.recordingRouteProgressGroup.visibility = View.GONE
            return
        }
        val reliablePoint = point?.takeIf {
            locationAgeMinutes(it) < STALE_LOCATION_MINUTES &&
                (it.accuracyMeters == null || it.accuracyMeters <= GpsQuality.RELIABLE_ACCURACY_METERS)
        }
        val observedProgress = reliablePoint?.let(tracker::update)
        val progress = observedProgress ?: tracker.currentOrInitial() ?: run {
            renderRecordingElevationProfile(
                source = route,
                progressDistanceMeters = null,
                plannedRoute = true,
            )
            binding.recordingRouteProgressGroup.visibility = View.GONE
            return
        }
        renderRecordingElevationProfile(
            source = route,
            progressDistanceMeters = progress.distanceAlongRouteMeters,
            plannedRoute = true,
        )
        binding.recordingRouteProgressGroup.visibility = View.VISIBLE
        val progressText = "${(progress.fraction * 100.0).roundToInt()} %"
        val remainingDistance = formatRemainingDistance(progress.remainingDistanceMeters)
        val eta = formatEstimatedArrival(progress)
        binding.recordingRouteProgressText.text = progressText
        binding.recordingRemainingDistanceText.text = remainingDistance
        binding.recordingEtaText.text = eta
    }

    private fun renderRecordingElevationProfile(
        source: GpxTrack,
        progressDistanceMeters: Double?,
        plannedRoute: Boolean,
    ) {
        if (source !== recordingElevationSource || plannedRoute != recordingElevationUsesPlannedRoute) {
            recordingElevationSource = source
            recordingElevationUsesPlannedRoute = plannedRoute
            val elevationProfile = TourInsightsAnalyzer.analyze(source).elevationProfile
            binding.recordingElevationChart.setElevationSeries(
                samples = elevationProfile,
                emptyMessage = getString(R.string.recording_no_elevation_data),
            )
        }
        binding.recordingElevationChart.setProgressDistance(
            progressDistanceMeters.takeIf { plannedRoute },
            Color.parseColor("#F26B38"),
        )
    }

    private fun renderRouteRejoinGuidance(point: TrackPoint?) {
        val recordingActive = latestSnapshot.state == RecordingState.RECORDING ||
            latestSnapshot.state == RecordingState.PAUSED
        val reliablePoint = point?.takeIf {
            locationAgeMinutes(it) < STALE_LOCATION_MINUTES &&
                (it.accuracyMeters == null || it.accuracyMeters <= GpsQuality.RELIABLE_ACCURACY_METERS)
        }
        if (!recordingActive || !latestSnapshot.confirmedOffRoute || reliablePoint == null) {
            binding.routeRejoinBanner.visibility = View.GONE
            return
        }
        val guidance = routeRejoinAdvisor?.advise(
            position = reliablePoint,
            progressAnchorMeters = routeProgressTracker?.currentOrInitial()?.distanceAlongRouteMeters,
        ) ?: run {
            binding.routeRejoinBanner.visibility = View.GONE
            return
        }
        val heading = latestCompassHeadingDegrees
        val arrowRotation = if (heading == null) {
            guidance.bearingDegrees
        } else {
            normalizeSignedAngle(guidance.bearingDegrees - heading)
        }
        val direction = if (heading == null) {
            getString(R.string.route_rejoin_compass_bearing, guidance.bearingDegrees.roundToInt())
        } else {
            getString(rejoinDirectionLabel(arrowRotation))
        }
        binding.routeRejoinArrow.rotation = arrowRotation.toFloat()
        binding.routeRejoinText.text = getString(
            R.string.route_rejoin_guidance,
            formatRemainingDistance(guidance.distanceMeters),
            direction,
        )
        binding.routeRejoinBanner.visibility = View.VISIBLE
    }

    private fun renderNavigationGuidance(guidance: NavigationGuidance?) {
        val recordingActive = latestSnapshot.state == RecordingState.RECORDING ||
            latestSnapshot.state == RecordingState.PAUSED
        if (!recordingActive || latestSnapshot.confirmedOffRoute || guidance == null) {
            binding.navigationManeuverBanner.visibility = View.GONE
            renderRouteStatusPresentation()
            return
        }
        binding.navigationManeuverArrow.rotation = navigationArrowRotation(guidance.maneuver.type)
        binding.navigationManeuverText.text = when {
            atDestination(guidance) -> getString(R.string.navigation_arrived)
            guidance.maneuver.type == NavigationManeuverType.ARRIVE -> getString(
                R.string.navigation_destination_in_distance,
                formatRemainingDistance(guidance.distanceMeters),
            )
            guidance.distanceMeters <= 22.0 -> getString(
                R.string.navigation_now,
                getString(navigationManeuverLabel(guidance.maneuver.type)),
            )
            else -> getString(
                R.string.navigation_in_distance,
                formatRemainingDistance(guidance.distanceMeters),
                getString(navigationManeuverLabel(guidance.maneuver.type)),
            )
        }
        binding.navigationManeuverBanner.visibility = View.VISIBLE
        renderRouteStatusPresentation()
    }

    private fun atDestination(guidance: NavigationGuidance): Boolean =
        guidance.maneuver.type == NavigationManeuverType.ARRIVE &&
            guidance.distanceMeters <= NavigationGuidanceTracker.ARRIVAL_ANNOUNCEMENT_METERS

    /**
     * Offers to finish the tour once the hiker stands at the destination. The prompt appears at
     * most once per recording session and never on top of another dialog.
     */
    private fun offerFinishAtDestination(snapshot: TrackingSnapshot) {
        val recordingActive = snapshot.state == RecordingState.RECORDING ||
            snapshot.state == RecordingState.PAUSED
        if (!recordingActive || snapshot.confirmedOffRoute) return
        val guidance = snapshot.navigationGuidance?.takeIf(::atDestination) ?: return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (arrivalPromptDialog?.isShowing == true || !hasWindowFocus()) return
        val sessionId = arrivalPromptSessionId() ?: return
        if (arrivalPromptShownForSessionId == sessionId) return
        arrivalPromptShownForSessionId = sessionId
        arrivalPromptDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.arrival_finish_title)
            .setMessage(R.string.arrival_finish_message)
            .setNegativeButton(R.string.arrival_keep_recording, null)
            .setPositiveButton(R.string.arrival_finish_tour) { _, _ ->
                sendTrackingAction(TrackingService.ACTION_STOP)
            }
            .setOnDismissListener { arrivalPromptDialog = null }
            .show()
    }

    /** Debug scenes render a snapshot without a stored session, so they share one fixed id. */
    private fun arrivalPromptSessionId(): Long? = trackStore.activeSession()?.id
        ?: DEBUG_SNAPSHOT_SESSION_ID.takeIf { debugSnapshotOverride }

    private fun navigationManeuverLabel(type: NavigationManeuverType): Int = when (type) {
        NavigationManeuverType.STRAIGHT -> R.string.navigation_straight
        NavigationManeuverType.SLIGHT_LEFT -> R.string.navigation_slight_left
        NavigationManeuverType.LEFT -> R.string.navigation_left
        NavigationManeuverType.SHARP_LEFT -> R.string.navigation_sharp_left
        NavigationManeuverType.SLIGHT_RIGHT -> R.string.navigation_slight_right
        NavigationManeuverType.RIGHT -> R.string.navigation_right
        NavigationManeuverType.SHARP_RIGHT -> R.string.navigation_sharp_right
        NavigationManeuverType.U_TURN -> R.string.navigation_u_turn
        NavigationManeuverType.ARRIVE -> R.string.navigation_destination
    }

    private fun navigationArrowRotation(type: NavigationManeuverType): Float = when (type) {
        NavigationManeuverType.STRAIGHT, NavigationManeuverType.ARRIVE -> 0f
        NavigationManeuverType.SLIGHT_RIGHT -> 40f
        NavigationManeuverType.RIGHT -> 90f
        NavigationManeuverType.SHARP_RIGHT -> 135f
        NavigationManeuverType.U_TURN -> 180f
        NavigationManeuverType.SHARP_LEFT -> -135f
        NavigationManeuverType.LEFT -> -90f
        NavigationManeuverType.SLIGHT_LEFT -> -40f
    }

    private fun rejoinDirectionLabel(relativeBearing: Double): Int {
        val angle = normalizeSignedAngle(relativeBearing)
        val absolute = kotlin.math.abs(angle)
        return when {
            absolute <= 22.5 -> R.string.route_rejoin_straight
            absolute <= 67.5 && angle > 0.0 -> R.string.route_rejoin_slight_right
            absolute <= 67.5 -> R.string.route_rejoin_slight_left
            absolute <= 135.0 && angle > 0.0 -> R.string.route_rejoin_right
            absolute <= 135.0 -> R.string.route_rejoin_left
            else -> R.string.route_rejoin_behind
        }
    }

    private fun normalizeSignedAngle(degrees: Double): Double =
        ((degrees + 540.0) % 360.0) - 180.0

    private fun formatRemainingDistance(distanceMeters: Double): String =
        if (distanceMeters < 1_000.0) "${distanceMeters.roundToInt()} m"
        else String.format(displayLocale, "%.1f km", distanceMeters / 1_000.0)

    private fun formatEstimatedArrival(progress: RouteProgress): String {
        if (progress.remainingDistanceMeters <= ROUTE_FINISHED_DISTANCE_METERS) {
            return getString(R.string.route_finished)
        }
        val forecast = TourForecaster.forecast(
            stats = TrackStats(
                distanceMeters = progress.remainingDistanceMeters,
                ascentMeters = progress.remainingAscentMeters,
                descentMeters = progress.remainingDescentMeters,
            ),
            elevationProfile = progress.remainingElevationProfile,
            fitnessLevel = fitnessPreferences.level,
        ) ?: return getString(R.string.not_available)
        return android.text.format.DateFormat.getTimeFormat(this).format(
            Date(System.currentTimeMillis() + forecast.totalDurationMillis),
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(compassRotationMatrix, event.values)
        SensorManager.getOrientation(compassRotationMatrix, compassOrientation)
        var heading = Math.toDegrees(compassOrientation[0].toDouble()).toFloat()
        val point = displayedPosition()
        if (point != null) {
            heading += GeomagneticField(
                point.latitude.toFloat(),
                point.longitude.toFloat(),
                (point.elevationMeters ?: 0.0).toFloat(),
                System.currentTimeMillis(),
            ).declination
        }
        val smoothed = headingSmoother.update(heading)
        val previous = latestCompassHeadingDegrees
        if (previous != null && HeadingSmoother.angularDistance(previous, smoothed) < MIN_HEADING_UPDATE_DEGREES) {
            return
        }
        latestCompassHeadingDegrees = smoothed
        if (mapOrientationMode == MapOrientationMode.HEADING_UP) {
            displayedPosition()?.let(::updateFollowCamera)
        }
        renderUserPosition()
        renderRouteRejoinGuidance(latestSnapshot.latestPoint ?: latestLocatedPoint)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        compassAccuracy = accuracy
    }

    private fun maybeShowCompassCalibrationHint() {
        if (
            latestCompassHeadingDegrees != null &&
            compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        ) {
            showRouteStatus(
                getString(R.string.compass_calibration_hint),
                R.color.warning,
                INFO_BADGE_MILLIS,
            )
        }
    }


    private fun clearLegacyCompassCorrection() {
        getSharedPreferences(LEGACY_COMPASS_PREFERENCES, MODE_PRIVATE)
            .edit()
            .remove(LEGACY_COMPASS_OFFSET_KEY)
            .apply()
    }

    private fun startCompassUpdates() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopCompassUpdates() {
        sensorManager.unregisterListener(this)
        headingSmoother.reset()
        latestCompassHeadingDegrees = null
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        binding.root.removeCallbacks(recordingTimeTickRunnable)
        binding.root.post(recordingTimeTickRunnable)
    }
    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        startCompassUpdates()
        if (hasLocationPermission()) {
            startVisibleLocationUpdates()
            locateUser(centerAfterFix = false)
        }
        offerFinishAtDestination(latestSnapshot)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Snapshots stop arriving while the window is covered, so re-check the arrival state once
        // the activity is in front again.
        if (hasFocus) offerFinishAtDestination(latestSnapshot)
    }
    override fun onPause() {
        stopCompassUpdates()
        stopVisibleLocationUpdates()
        locationRequestSignals.forEach(CancellationSignal::cancel)
        locationRequestSignals.clear()
        binding.mapView.onPause()
        super.onPause()
    }
    override fun onStop() {
        binding.root.removeCallbacks(recordingTimeTickRunnable)
        binding.mapView.onStop()
        super.onStop()
    }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() {
        arrivalPromptDialog?.dismiss()
        arrivalPromptDialog = null
        binding.routeStatusText.removeCallbacks(restoreRouteStatusRunnable)
        locationRequestSignals.forEach(CancellationSignal::cancel)
        locationRequestSignals.clear()
        binding.mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
        outState.putString(KEY_MAP_ORIENTATION_MODE, mapOrientationMode.name)
        arrivalPromptShownForSessionId?.let { outState.putLong(KEY_ARRIVAL_PROMPT_SESSION, it) }
    }

    companion object {
        const val EXTRA_TOUR_REFERENCE = "de.wandern.app.MAIN_TOUR_REFERENCE"
        const val EXTRA_OFFER_OFFLINE_MAP = "de.wandern.app.OFFER_OFFLINE_MAP"
        const val ACTION_DEBUG_SCENARIO = "de.wandern.app.DEBUG_SCENARIO"
        const val EXTRA_DEBUG_SCENARIO = "scenario"
        const val EXTRA_DEBUG_START_RECORDING = "de.wandern.app.DEBUG_START_RECORDING"
        const val EXTRA_DEBUG_ROUTE_NAME = "de.wandern.app.DEBUG_ROUTE_NAME"
        const val EXTRA_DEBUG_ACTIVITY_TYPE = "de.wandern.app.DEBUG_ACTIVITY_TYPE"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val LOG_TAG = "WandernImport"
        private const val RECORDING_TIME_TICK_MILLIS = 1_000L
        private const val KEY_ARRIVAL_PROMPT_SESSION = "arrival_prompt_session"
        private const val KEY_MAP_ORIENTATION_MODE = "map_orientation_mode"
        private const val DEBUG_SNAPSHOT_SESSION_ID = -1L
        private const val COMPLETION_PRESENTATION_PREFERENCES = "completion_presentation"
        private const val KEY_LAST_PRESENTED_RECORDING = "last_presented_recording"
        private const val LEGACY_COMPASS_PREFERENCES = "compass_preferences"
        private const val LEGACY_COMPASS_OFFSET_KEY = "heading_offset_degrees"
        private const val ROUTE_SOURCE = "imported-route-source"
        private const val ROUTE_LAYER = "imported-route-layer"
        private const val ROUTE_DIRECTION_LAYER = "imported-route-direction-layer"
        private const val ROUTE_DIRECTION_ICON = "imported-route-direction-icon"
        private const val CLOSURE_SOURCE = "route-closure-source"
        private const val CLOSURE_HALO_LAYER = "route-closure-halo-layer"
        private const val CLOSURE_LAYER = "route-closure-layer"
        private const val DETOUR_SOURCE = "active-detour-source"
        private const val DETOUR_LAYER = "active-detour-layer"
        private const val LIVE_TRACK_SOURCE = "live-track-source"
        private const val LIVE_TRACK_LAYER = "live-track-layer"
        private const val INTERPOLATED_TRACK_SOURCE = "interpolated-track-source"
        private const val INTERPOLATED_TRACK_LAYER = "interpolated-track-layer"
        private const val POSITION_SOURCE = "position-source"
        private const val POSITION_HALO_LAYER = "position-halo-layer"
        private const val POSITION_LAYER = "position-layer"
        private const val POSITION_DIRECTION_SOURCE = "position-direction-source"
        private const val POSITION_DIRECTION_LAYER = "position-direction-layer"
        private const val POSITION_DIRECTION_ICON = "position-direction-icon"
        private const val OFF_ROUTE_THRESHOLD_METERS = 50.0
        private const val STALE_LOCATION_MINUTES = 2
        private const val SIGNIFICANT_LOCATION_TIME_MILLIS = 120_000L
        private const val INITIAL_REGION_ZOOM = 4.0
        private const val USER_FOCUS_ZOOM = 16.0
        private const val FOLLOW_RECENTER_METERS = 2.0
        private const val FOLLOW_CAMERA_MILLIS = 400
        private const val COMPASS_ALIGN_MILLIS = 400
        private const val HEADING_UP_MIN_TURN_DEGREES = 3f
        private const val HEADING_UP_MIN_INTERVAL_MILLIS = 300L
        private const val HEADING_UP_MIN_SPEED_METERS_PER_SECOND = 1f
        private const val MIN_DIRECTION_SPEED_METERS_PER_SECOND = 0.6f
        private const val MIN_HEADING_UPDATE_DEGREES = 1f
        private const val CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS = 50.0
        private const val ROUTE_FINISHED_DISTANCE_METERS = 25.0
        private const val LOW_BATTERY_WARNING_PERCENT = 20
        private const val START_DIALOG_TOTAL_MARGIN_DP = 24
        private const val START_DIALOG_MAX_WIDTH_DP = 560
        private const val ROUTE_LOADED_BADGE_MILLIS = 3_000L
        private const val INFO_BADGE_MILLIS = 4_000L
        private const val SUCCESS_BADGE_MILLIS = 4_000L
        private const val ERROR_BADGE_MILLIS = 8_000L
        private const val MENU_EDIT_ROUTE = 2
        private const val MENU_CLEAR_ROUTE = 3
        private const val MENU_FIT_ROUTE = 5
        private const val MENU_REVERSE_ROUTE = 6
        private const val MENU_VOICE_GUIDANCE = 7
        private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        private const val RECORDING_PAGE_STATS = 0
        private const val RECORDING_PAGE_ELEVATION = 1
    }

    private data class StartCheckLine(val text: String, val warning: Boolean)
    private data class BatteryState(val percent: Int, val charging: Boolean)
}
