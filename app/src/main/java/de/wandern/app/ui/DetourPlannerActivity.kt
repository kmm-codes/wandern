package de.wandern.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.R
import de.wandern.app.data.DetourSessionStore
import de.wandern.app.data.OnlineRoutingClient
import de.wandern.app.data.RecordingRouteStore
import de.wandern.app.data.TrackStore
import de.wandern.app.localization.AppLanguage
import de.wandern.app.databinding.ActivityDetourPlannerBinding
import de.wandern.app.model.ActivityType
import de.wandern.app.model.DetourCorridor
import de.wandern.app.model.DetourPlanner
import de.wandern.app.model.DetourRouteCandidate
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.RoutePath
import de.wandern.app.model.RouteAdjustmentKind
import de.wandern.app.model.TrackAnalyzer
import de.wandern.app.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class DetourPlannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetourPlannerBinding
    private val displayLocale get() = AppLanguage.forContext(this).locale
    private lateinit var trackStore: TrackStore
    private lateinit var detourStore: DetourSessionStore
    private lateinit var recordingRouteStore: RecordingRouteStore
    private val routingClient = OnlineRoutingClient()
    private var map: MapLibreMap? = null
    private var mapStyle: Style? = null
    private var sessionId = -1L
    private var originalRouteReference: String? = null
    private var restoresRecordingRoute = false
    private lateinit var route: GpxTrack
    private lateinit var routePath: RoutePath
    private lateinit var currentPosition: TrackPoint
    private var currentProgressMeters = 0.0
    private var corridorLengthMeters = DetourPlanner.DEFAULT_CORRIDOR_LENGTH_METERS
    private var corridor: DetourCorridor? = null
    private var candidates: List<DetourRouteCandidate> = emptyList()
    private var selectedCandidateIndex = 0
    private var routing = false
    private var plannerMode = PlannerMode.DETOUR

    private val selectedCandidate: DetourRouteCandidate?
        get() = candidates.getOrNull(selectedCandidateIndex)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityDetourPlannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        trackStore = TrackStore(this)
        detourStore = DetourSessionStore(this)
        recordingRouteStore = RecordingRouteStore(this)
        if (!loadInputs()) {
            toast(getString(R.string.detour_needs_route))
            finish()
            return
        }
        configurePlannerMode()
        setupActions()
        setupMap()
        if (plannerMode == PlannerMode.REJOIN) {
            prepareRejoinPlanner()
            binding.root.post(::calculateRejoin)
        } else {
            updateCorridor()
        }
    }

    private fun loadInputs(): Boolean {
        plannerMode = if (intent.getStringExtra(EXTRA_MODE) == MODE_REJOIN) {
            PlannerMode.REJOIN
        } else {
            PlannerMode.DETOUR
        }
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val session = trackStore.activeSession()?.takeIf { it.id == sessionId } ?: return false
        val existingDetour = detourStore.load(sessionId)
        val recordingRoute = recordingRouteStore.load(sessionId)
        originalRouteReference = existingDetour?.originalRouteReference ?: session.routeReference
        restoresRecordingRoute = existingDetour?.restoresRecordingRoute ?: (recordingRoute != null)
        route = existingDetour?.route
            ?: recordingRoute?.route
            ?: session.routeReference?.let { reference ->
                runCatching { trackStore.loadStoredTrack(reference) }.getOrNull()
            }
            ?: return false
        if (route.points.size < 2) return false
        routePath = RoutePath(route)
        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return false
        currentPosition = TrackPoint(latitude, longitude)
        currentProgressMeters = intent.getDoubleExtra(EXTRA_PROGRESS_METERS, 0.0)
            .coerceIn(0.0, routePath.totalDistanceMeters)
        return true
    }

    private fun configurePlannerMode() {
        if (plannerMode != PlannerMode.REJOIN) return
        binding.toolbar.setTitle(R.string.route_rejoin_title)
        binding.plannerTitleText.setText(R.string.route_rejoin_title)
        binding.instructionText.setText(R.string.route_rejoin_instruction)
        binding.corridorLengthText.visibility = View.GONE
        binding.corridorLengthSlider.visibility = View.GONE
        binding.findDetourButton.setText(R.string.route_rejoin_find)
        binding.useDetourButton.setText(R.string.route_rejoin_use)
    }

    private fun setupActions() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.findDetourButton.setOnClickListener {
            if (plannerMode == PlannerMode.REJOIN) calculateRejoin() else calculateDetour()
        }
        binding.useDetourButton.setOnClickListener { selectedCandidate?.let(::confirmAndUse) }
        binding.previousDetourButton.setOnClickListener { selectCandidate(-1) }
        binding.nextDetourButton.setOnClickListener { selectCandidate(1) }
        binding.corridorLengthSlider.max = (
            DetourPlanner.MAX_CORRIDOR_LENGTH_METERS - DetourPlanner.MIN_CORRIDOR_LENGTH_METERS
            ).roundToInt()
        binding.corridorLengthSlider.progress = (
            DetourPlanner.DEFAULT_CORRIDOR_LENGTH_METERS - DetourPlanner.MIN_CORRIDOR_LENGTH_METERS
            ).roundToInt()
        binding.corridorLengthSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                corridorLengthMeters = DetourPlanner.MIN_CORRIDOR_LENGTH_METERS + progress
                updateCorridor()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupMap() {
        binding.mapView.getMapAsync { readyMap ->
            map = readyMap.apply {
                uiSettings.isCompassEnabled = true
                uiSettings.isAttributionEnabled = true
                uiSettings.isLogoEnabled = true
                if (plannerMode == PlannerMode.DETOUR) addOnMapClickListener(::setCorridorEndFromMap)
            }
            readyMap.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                MapStyleLocalizer.localize(style, AppLanguage.forContext(this))
                mapStyle = style
                addLineLayer(style, ROUTE_SOURCE, ROUTE_LAYER, "#1677FF", 6f, 0.72f)
                addLineLayer(style, PREVIEW_SOURCE, PREVIEW_LAYER, "#F28C28", 7f, 0.95f)
                addLineLayer(style, CORRIDOR_SOURCE, CORRIDOR_HALO_LAYER, "#FFFFFF", 14f, 0.9f)
                addLineLayer(style, CORRIDOR_SOURCE, CORRIDOR_LAYER, "#C44431", 9f, 0.98f)
                addPointLayer(style, POSITION_SOURCE, POSITION_LAYER, "#1677FF")
                addPointLayer(style, CORRIDOR_END_SOURCE, CORRIDOR_END_LAYER, "#C44431")
                updateLineSource(ROUTE_SOURCE, route)
                updatePointSource(POSITION_SOURCE, currentPosition)
                updateCorridorSource()
                if (plannerMode == PlannerMode.DETOUR) {
                    frameCorridor()
                } else {
                    readyMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(currentPosition.latitude, currentPosition.longitude),
                            14.5,
                        ),
                    )
                }
            }
        }
    }

    private fun addLineLayer(
        style: Style,
        sourceId: String,
        layerId: String,
        color: String,
        width: Float,
        opacity: Float,
    ) {
        if (style.getSource(sourceId) == null) style.addSource(GeoJsonSource(sourceId, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            LineLayer(layerId, sourceId).withProperties(
                lineColor(Color.parseColor(color)),
                lineWidth(width),
                lineOpacity(opacity),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
    }

    private fun addPointLayer(style: Style, sourceId: String, layerId: String, color: String) {
        style.addSource(GeoJsonSource(sourceId, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            CircleLayer(layerId, sourceId).withProperties(
                circleColor(Color.parseColor(color)),
                circleRadius(9f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(3f),
            ),
        )
    }

    private fun updateCorridor() {
        corridor = runCatching {
            DetourPlanner.corridor(route, currentProgressMeters, corridorLengthMeters)
        }.getOrElse {
            binding.instructionText.text = it.localizedMessage
            null
        }
        candidates = emptyList()
        selectedCandidateIndex = 0
        binding.useDetourButton.isEnabled = false
        binding.resultText.visibility = View.GONE
        binding.detourAlternativeSelector.visibility = View.GONE
        updateLineSource(PREVIEW_SOURCE, null)
        binding.corridorLengthText.text = getString(
            R.string.detour_corridor_length,
            corridor?.let { (it.endDistanceMeters - it.startDistanceMeters).roundToInt() }
                ?: corridorLengthMeters.roundToInt(),
        )
        updateCorridorSource()
    }

    private fun prepareRejoinPlanner() {
        corridor = null
        candidates = emptyList()
        selectedCandidateIndex = 0
        binding.useDetourButton.isEnabled = false
        binding.resultText.visibility = View.GONE
        binding.detourAlternativeSelector.visibility = View.GONE
        updateLineSource(PREVIEW_SOURCE, null)
        updateCorridorSource()
    }

    private fun setCorridorEndFromMap(position: LatLng): Boolean {
        if (routing) return true
        val existing = corridor ?: return true
        val selected = routePath.nearestDistanceAlongRoute(
            TrackPoint(position.latitude, position.longitude),
            minimumDistanceMeters = existing.startDistanceMeters + DetourPlanner.MIN_CORRIDOR_LENGTH_METERS,
            maximumDistanceMeters = (existing.startDistanceMeters + DetourPlanner.MAX_CORRIDOR_LENGTH_METERS)
                .coerceAtMost(routePath.totalDistanceMeters - 80.0),
        ) ?: return true
        val length = (selected - existing.startDistanceMeters)
            .coerceIn(DetourPlanner.MIN_CORRIDOR_LENGTH_METERS, DetourPlanner.MAX_CORRIDOR_LENGTH_METERS)
        binding.corridorLengthSlider.progress =
            (length - DetourPlanner.MIN_CORRIDOR_LENGTH_METERS).roundToInt()
        frameCorridor()
        return true
    }

    private fun calculateDetour() {
        val selectedCorridor = corridor ?: return
        if (routing) return
        if (!hasInternetConnection()) {
            toast(getString(R.string.detour_needs_internet))
            return
        }
        routing = true
        candidates = emptyList()
        selectedCandidateIndex = 0
        binding.findDetourButton.isEnabled = false
        binding.useDetourButton.isEnabled = false
        binding.corridorLengthSlider.isEnabled = false
        binding.routingProgress.visibility = View.VISIBLE
        binding.instructionText.setText(R.string.detour_calculating)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { findCandidates(selectedCorridor) }
            routing = false
            binding.findDetourButton.isEnabled = true
            binding.corridorLengthSlider.isEnabled = true
            binding.routingProgress.visibility = View.GONE
            result.onSuccess { found ->
                candidates = found
                selectedCandidateIndex = 0
                binding.instructionText.setText(R.string.detour_corridor_instruction)
                renderSelectedCandidate(frame = true)
            }.onFailure {
                binding.resultText.visibility = View.VISIBLE
                binding.resultText.setText(R.string.detour_no_route)
                binding.instructionText.setText(R.string.detour_corridor_instruction)
                updateLineSource(PREVIEW_SOURCE, null)
            }
        }
    }

    private fun calculateRejoin() {
        if (routing) return
        if (!hasInternetConnection()) {
            toast(getString(R.string.detour_needs_internet))
            return
        }
        routing = true
        candidates = emptyList()
        selectedCandidateIndex = 0
        binding.findDetourButton.isEnabled = false
        binding.useDetourButton.isEnabled = false
        binding.routingProgress.visibility = View.VISIBLE
        binding.instructionText.setText(R.string.route_rejoin_calculating)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { findRejoinCandidates() }
            routing = false
            binding.findDetourButton.isEnabled = true
            binding.routingProgress.visibility = View.GONE
            result.onSuccess { found ->
                candidates = found
                selectedCandidateIndex = 0
                binding.instructionText.setText(R.string.route_rejoin_instruction)
                renderSelectedCandidate(frame = true)
            }.onFailure {
                binding.resultText.visibility = View.VISIBLE
                binding.resultText.setText(R.string.route_rejoin_no_route)
                binding.instructionText.setText(R.string.route_rejoin_instruction)
                updateLineSource(PREVIEW_SOURCE, null)
            }
        }
    }

    private fun findRejoinCandidates(): Result<List<DetourRouteCandidate>> = runCatching {
        val activityType = route.activityType ?: ActivityType.HIKING
        var lastError: Throwable? = null
        val found = mutableListOf<DetourRouteCandidate>()
        DetourPlanner.rejoinDistances(route, currentPosition, currentProgressMeters).forEach { rejoinDistance ->
            val rejoinPoint = routePath.pointAt(rejoinDistance)
            runCatching {
                routingClient.calculate(
                    waypoints = listOf(currentPosition, rejoinPoint),
                    activityType = activityType,
                    routeName = getString(R.string.route_rejoin_title),
                )
            }.onSuccess { connector ->
                found += DetourPlanner.combineRejoin(
                    route = route,
                    currentProgressMeters = currentProgressMeters,
                    connector = connector,
                    rejoinDistanceMeters = rejoinDistance,
                )
            }.onFailure { lastError = it }
        }
        if (found.isEmpty()) throw lastError ?: IllegalStateException(getString(R.string.route_rejoin_no_route))
        found.sortedBy { candidate ->
            TrackAnalyzer.calculate(candidate.detourTrack).distanceMeters +
                candidate.skippedRouteMeters * REJOIN_SKIP_PENALTY
        }
    }

    private fun findCandidates(selectedCorridor: DetourCorridor): Result<List<DetourRouteCandidate>> = runCatching {
        val activityType = route.activityType ?: ActivityType.HIKING
        var lastError: Throwable? = null
        val found = mutableListOf<DetourRouteCandidate>()
        for (rejoinDistance in DetourPlanner.rejoinDistances(route, selectedCorridor)) {
            if (found.size >= MAX_DETOUR_CANDIDATES) break
            val rejoinPoint = routePath.pointAt(rejoinDistance)
            runCatching {
                routingClient.calculate(
                    waypoints = listOf(currentPosition, rejoinPoint),
                    activityType = activityType,
                    routeName = getString(R.string.detour_route_short_name),
                    noGoPoints = selectedCorridor.noGoPoints,
                )
            }.onSuccess { detour ->
                found += DetourPlanner.combine(
                    route = route,
                    currentProgressMeters = currentProgressMeters,
                    corridor = selectedCorridor,
                    detour = detour,
                    rejoinDistanceMeters = rejoinDistance,
                )
            }.onFailure { lastError = it }
        }
        if (found.isNotEmpty()) return@runCatching found
        val destination = route.points.last()
        val direct = runCatching {
            routingClient.calculate(
                waypoints = listOf(currentPosition, destination),
                activityType = activityType,
                routeName = getString(R.string.detour_route_name),
                noGoPoints = selectedCorridor.noGoPoints,
            )
        }.getOrElse { throw lastError ?: it }
        listOf(
            DetourPlanner.combine(
                route = route,
                currentProgressMeters = currentProgressMeters,
                corridor = selectedCorridor,
                detour = direct,
                rejoinDistanceMeters = routePath.totalDistanceMeters,
                directToDestination = true,
            ),
        )
    }

    private fun selectCandidate(delta: Int) {
        if (candidates.size < 2) return
        selectedCandidateIndex = (selectedCandidateIndex + delta + candidates.size) % candidates.size
        renderSelectedCandidate(frame = false)
    }

    private fun renderSelectedCandidate(frame: Boolean) {
        val found = selectedCandidate ?: return
        binding.useDetourButton.isEnabled = true
        binding.resultText.visibility = View.VISIBLE
        binding.resultText.text = describe(found)
        binding.detourAlternativeSelector.visibility = if (candidates.size > 1) View.VISIBLE else View.GONE
        binding.detourAlternativeLabel.text = getString(
            if (plannerMode == PlannerMode.REJOIN) {
                R.string.route_rejoin_alternative_label
            } else {
                R.string.detour_alternative_label
            },
            selectedCandidateIndex + 1,
            candidates.size,
        )
        updateLineSource(PREVIEW_SOURCE, found.detourTrack)
        if (frame) framePreview(found.detourTrack)
    }

    private fun describe(found: DetourRouteCandidate): String {
        if (plannerMode == PlannerMode.REJOIN) {
            val connectorDistance = formatDistance(TrackAnalyzer.calculate(found.detourTrack).distanceMeters)
            return if (found.directToDestination) {
                getString(R.string.route_rejoin_direct_result, connectorDistance)
            } else {
                getString(
                    R.string.route_rejoin_result,
                    connectorDistance,
                    String.format(displayLocale, "%.1f", found.rejoinDistanceMeters / 1_000.0),
                )
            }
        }
        val change = formatDistance(found.extraDistanceMeters.absoluteValue)
        if (found.directToDestination) {
            return getString(R.string.detour_direct_result, signedDistance(found.extraDistanceMeters))
        }
        val untilRejoin = formatDistance(
            (found.rejoinDistanceMeters - currentProgressMeters).coerceAtLeast(0.0),
        )
        return if (found.extraDistanceMeters >= 0.0) {
            getString(R.string.detour_result, untilRejoin, change)
        } else {
            getString(R.string.detour_result_shorter, untilRejoin, change)
        }
    }

    private fun confirmAndUse(found: DetourRouteCandidate) {
        if (found.requiresConfirmation) {
            MaterialAlertDialogBuilder(this)
                .setTitle(
                    if (plannerMode == PlannerMode.REJOIN) {
                        R.string.route_rejoin_large_title
                    } else {
                        R.string.detour_large_title
                    },
                )
                .setMessage(
                    if (plannerMode == PlannerMode.REJOIN) {
                        R.string.route_rejoin_large_message
                    } else {
                        R.string.detour_large_message
                    },
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                    if (plannerMode == PlannerMode.REJOIN) {
                        R.string.route_rejoin_use
                    } else {
                        R.string.detour_use
                    },
                ) { _, _ -> saveAndFinish(found) }
                .show()
        } else {
            saveAndFinish(found)
        }
    }

    private fun saveAndFinish(found: DetourRouteCandidate) {
        val corridorStart = corridor?.startDistanceMeters ?: currentProgressMeters
        val corridorEnd = corridor?.endDistanceMeters ?: currentProgressMeters
        runCatching {
            detourStore.save(
                sessionId = sessionId,
                originalRouteReference = originalRouteReference,
                restoresRecordingRoute = restoresRecordingRoute,
                candidate = found,
                corridorStartMeters = corridorStart,
                corridorEndMeters = corridorEnd,
                kind = if (plannerMode == PlannerMode.REJOIN) {
                    RouteAdjustmentKind.REJOIN
                } else {
                    RouteAdjustmentKind.DETOUR
                },
            )
        }.onSuccess {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SESSION_ID, sessionId))
            finish()
        }.onFailure { toast(it.localizedMessage ?: getString(R.string.detour_no_route)) }
    }

    private fun updateCorridorSource() {
        val points = corridor?.points.orEmpty()
        val source = mapStyle?.getSourceAs<GeoJsonSource>(CORRIDOR_SOURCE) ?: return
        source.setGeoJson(points.toFeatureCollection())
        updatePointSource(CORRIDOR_END_SOURCE, points.lastOrNull())
    }

    private fun updatePointSource(sourceId: String, point: TrackPoint?) {
        val source = mapStyle?.getSourceAs<GeoJsonSource>(sourceId) ?: return
        source.setGeoJson(
            point?.let {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)),
                )
            } ?: FeatureCollection.fromFeatures(emptyList()),
        )
    }

    private fun updateLineSource(sourceId: String, track: GpxTrack?) {
        val source = mapStyle?.getSourceAs<GeoJsonSource>(sourceId) ?: return
        val features = track?.segments.orEmpty().mapNotNull { segment ->
            if (segment.size < 2) null else Feature.fromGeometry(
                LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun List<TrackPoint>.toFeatureCollection(): FeatureCollection =
        if (size < 2) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(map { Point.fromLngLat(it.longitude, it.latitude) })),
        )

    private fun frameCorridor() {
        val points = listOf(currentPosition) + corridor?.points.orEmpty()
        framePoints(points)
    }

    private fun framePreview(track: GpxTrack) {
        framePoints(listOf(currentPosition) + corridor?.points.orEmpty() + track.points)
    }

    private fun framePoints(points: List<TrackPoint>) {
        val readyMap = map ?: return
        if (points.isEmpty()) return
        val bounds = LatLngBounds.Builder().also { builder ->
            points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        }.build()
        binding.mapView.post {
            val side = (24 * resources.displayMetrics.density).roundToInt()
            val bottom = (310 * resources.displayMetrics.density).roundToInt()
            readyMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, side, side * 3, side, bottom), 500)
        }
    }

    private fun formatDistance(meters: Double): String = if (meters < 1_000.0) {
        "${meters.roundToInt()} m"
    } else {
        String.format(displayLocale, "%.1f km", meters / 1_000.0)
    }

    private fun signedDistance(meters: Double): String =
        (if (meters >= 0.0) "+" else "−") + formatDistance(meters.absoluteValue)

    private fun hasInternetConnection(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { binding.mapView.onPause(); super.onPause() }
    override fun onStop() { binding.mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() { binding.mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_SESSION_ID = "de.wandern.app.extra.DETOUR_SESSION_ID"
        const val EXTRA_LATITUDE = "de.wandern.app.extra.DETOUR_LATITUDE"
        const val EXTRA_LONGITUDE = "de.wandern.app.extra.DETOUR_LONGITUDE"
        const val EXTRA_PROGRESS_METERS = "de.wandern.app.extra.DETOUR_PROGRESS_METERS"
        const val EXTRA_MODE = "de.wandern.app.extra.DETOUR_MODE"
        const val MODE_REJOIN = "rejoin"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val ROUTE_SOURCE = "detour-original-source"
        private const val ROUTE_LAYER = "detour-original-layer"
        private const val PREVIEW_SOURCE = "detour-preview-source"
        private const val PREVIEW_LAYER = "detour-preview-layer"
        private const val CORRIDOR_SOURCE = "detour-corridor-source"
        private const val CORRIDOR_HALO_LAYER = "detour-corridor-halo-layer"
        private const val CORRIDOR_LAYER = "detour-corridor-layer"
        private const val POSITION_SOURCE = "detour-position-source"
        private const val POSITION_LAYER = "detour-position-layer"
        private const val CORRIDOR_END_SOURCE = "detour-corridor-end-source"
        private const val CORRIDOR_END_LAYER = "detour-corridor-end-layer"
        private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        private const val MAX_DETOUR_CANDIDATES = 3
        private const val REJOIN_SKIP_PENALTY = 0.35
    }

    private enum class PlannerMode {
        DETOUR,
        REJOIN,
    }
}
