package de.wandern.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.R
import de.wandern.app.data.ActivityPreferences
import de.wandern.app.data.DetourSessionStore
import de.wandern.app.data.OnlineRoutingClient
import de.wandern.app.data.PlaceSearchClient
import de.wandern.app.data.PlaceSearchResult
import de.wandern.app.data.RecordingRouteStore
import de.wandern.app.data.RoutingNoGoPoint
import de.wandern.app.data.TrackStore
import de.wandern.app.localization.AppLanguage
import de.wandern.app.databinding.ActivityRoutePlannerBinding
import de.wandern.app.databinding.ItemRouteWaypointBinding
import de.wandern.app.model.ActivityType
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.PlaceSearchRanker
import de.wandern.app.model.TrackPoint
import de.wandern.app.model.TrackAnalyzer
import de.wandern.app.model.RouteVariantPolicy
import de.wandern.app.model.RouteControlPointExtractor
import de.wandern.app.model.asRouteDefinition
import de.wandern.app.model.WaypointOrdering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

class RoutePlannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRoutePlannerBinding
    private lateinit var trackStore: TrackStore
    private lateinit var detourStore: DetourSessionStore
    private lateinit var recordingRouteStore: RecordingRouteStore
    private lateinit var locationManager: LocationManager
    private val routingClient = OnlineRoutingClient()
    private val placeSearchClient = PlaceSearchClient()
    private val waypoints = mutableListOf<TrackPoint>()
    private val waypointNames = mutableMapOf<TrackPoint, String>()
    private var destinationDraft: TrackPoint? = null
    private val editHistory = mutableListOf<PlannerUndoState>()
    private val waypointMarkers = mutableListOf<Marker>()
    private var userLocationMarker: Marker? = null
    private var map: MapLibreMap? = null
    private var mapStyle: Style? = null
    private var calculatedRoute: GpxTrack? = null
    private var routeAlternatives: List<GpxTrack> = emptyList()
    private var selectedAlternativeIndex = 0
    private var roundTripPhase = RoundTripPhase.NONE
    private var activityType = ActivityType.HIKING
    private var routeMode = RouteMode.ONE_WAY
    private var routingInProgress = false
    private var searchInProgress = false
    private var locationInProgress = false
    private var pendingMapRole: PointRole? = null
    private var pendingMapViaIndex: Int? = null
    private var pendingLocationRole: PointRole? = null
    private var pendingLocationViaIndex: Int? = null
    private var pendingHomeSave = false
    private var locationSignal: CancellationSignal? = null
    private var centerLocationSignal: CancellationSignal? = null
    private var locationLookupJob: Job? = null
    private var pointSearchRole: PointRole? = null
    private var editingViaIndex: Int? = null
    private var pointSearchJob: Job? = null
    private var autoRoutingJob: Job? = null
    private var pointSearchGeneration = 0
    private lateinit var waypointAdapter: WaypointAdapter
    private lateinit var waypointTouchHelper: ItemTouchHelper
    private lateinit var plannerSheetBehavior: BottomSheetBehavior<MaterialCardView>
    private lateinit var plannerDrawerController: BottomDrawerController<MaterialCardView>
    private var waypointDragStart: List<TrackPoint>? = null
    private var waypointEditorExpanded = true
    private var preserveExpandedWaypointEditorAfterRouting = false
    private var editTourReference: String? = null
    private var editTourName: String? = null
    private var editBaseline: EditBaseline? = null
    private var editLoadInProgress = false
    private var recordingSessionId: Long? = null
    private var initialSearchReference: TrackPoint? = null
    private var pendingCenterRequest = false
    private var pendingFramePoints: List<TrackPoint>? = null
    private var drawerExtentUpdatePosted = false
    private var plannerDrawerOperationLocked = false
    private var drawerPullCandidate = false
    private var drawerOverPullActive = false
    private var drawerPullStartX = 0f
    private var drawerPullStartY = 0f
    private var drawerSpring: SpringAnimation? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true || hasLocationPermission()
        if (pendingCenterRequest) {
            pendingCenterRequest = false
            if (granted) centerOnUser() else toast(getString(R.string.location_permission_for_map))
            return@registerForActivityResult
        }
        val role = pendingLocationRole ?: PointRole.START
        val viaIndex = pendingLocationViaIndex
        pendingLocationRole = null
        pendingLocationViaIndex = null
        if (granted) {
            locateForRole(role, viaIndex)
        } else {
            pendingHomeSave = false
            toast(getString(R.string.location_permission_for_planning))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityRoutePlannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupPlannerBottomSheet()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            plannerDrawerController.applyWindowInsets(insets)
            schedulePlannerExtentUpdate()
            insets
        }
        binding.root.post { ViewCompat.requestApplyInsets(binding.root) }
        trackStore = TrackStore(this)
        detourStore = DetourSessionStore(this)
        recordingRouteStore = RecordingRouteStore(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        activityType = ActivityPreferences(this).defaultType
        initialSearchReference = intentSearchReference()

        binding.toolbar.setNavigationContentDescription(R.string.cancel)
        binding.toolbar.setNavigationOnClickListener { attemptExitPlanner() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_undo_route_edit -> {
                    undoEdit()
                    true
                }
                R.id.action_save_route -> {
                    askForRouteName()
                    true
                }
                else -> false
            }
        }
        binding.activityTypeButton.setOnClickListener { chooseActivityType() }
        binding.routeModeButton.setOnClickListener { chooseRouteMode() }
        binding.startPointButton.setOnClickListener { openPointSearch(PointRole.START) }
        binding.destinationPointButton.setOnClickListener { openPointSearch(PointRole.DESTINATION) }
        binding.addViaButton.setOnClickListener { openPointSearch(PointRole.VIA) }
        binding.swapEndpointsButton.setOnClickListener { reverseRouteDirection() }
        binding.previousAlternativeButton.setOnClickListener { selectAlternative(-1) }
        binding.nextAlternativeButton.setOnClickListener { selectAlternative(1) }
        binding.centerButton.setOnClickListener { requestCenterOnUser() }
        binding.drawerCompactHeader.setOnClickListener {
            if (plannerSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                plannerDrawerController.expand()
            }
        }
        binding.calculateButton.setOnClickListener {
            when {
                routeMode == RouteMode.ROUND_TRIP && roundTripPhase == RoundTripPhase.OUTBOUND ->
                    calculateRoundTripReturns()
                routeMode == RouteMode.ROUND_TRIP && roundTripPhase == RoundTripPhase.RETURN ->
                    confirmRoundTripReturn()
                calculatedRoute != null -> askForRouteName()
            }
        }
        setupWaypointList()
        setupPointSearch()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.pointSearchOverlay.visibility == View.VISIBLE) closePointSearch() else attemptExitPlanner()
        }
        setupMap()
        renderPlannerState()
        if (savedInstanceState == null) {
            val sessionId = intent.getLongExtra(EXTRA_RECORDING_SESSION_ID, -1L).takeIf { it >= 0L }
            val reference = intent.getStringExtra(EXTRA_EDIT_TOUR_REFERENCE)
            if (sessionId != null) loadRecordingRouteForEditing(sessionId)
            else if (reference != null) loadTourForEditing(reference)
            else if (intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false)) {
                binding.root.post { openPointSearch(PointRole.START) }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerSpring?.cancel()
                if (binding.plannerCard.translationY != 0f) {
                    binding.plannerCard.translationY = 0f
                    updateCenterButtonPosition()
                }
                drawerPullCandidate = binding.plannerCard.visibility == View.VISIBLE &&
                    !plannerDrawerOperationLocked &&
                    plannerSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED &&
                    isInsidePlannerDrawer(event.rawX.toInt(), event.rawY.toInt())
                drawerOverPullActive = false
                drawerPullStartX = event.rawX
                drawerPullStartY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - drawerPullStartX
                val upwardDistance = drawerPullStartY - event.rawY
                if (
                    drawerPullCandidate &&
                    !drawerOverPullActive &&
                    upwardDistance > ViewConfiguration.get(this).scaledTouchSlop &&
                    upwardDistance > abs(deltaX) &&
                    plannerContentFitsAvailableHeight()
                ) {
                    drawerOverPullActive = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (drawerOverPullActive) {
                    applyDrawerOverPull(upwardDistance.coerceAtLeast(0f))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawerPullCandidate = false
                if (drawerOverPullActive) {
                    drawerOverPullActive = false
                    springDrawerBack()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInsidePlannerDrawer(screenX: Int, screenY: Int): Boolean {
        val bounds = Rect()
        return binding.plannerCard.getGlobalVisibleRect(bounds) && bounds.contains(screenX, screenY)
    }

    private fun plannerContentFitsAvailableHeight(): Boolean {
        val viewportHeight = binding.plannerScroll.height
        return viewportHeight > 0 && binding.plannerContent.height <= viewportHeight
    }

    private fun applyDrawerOverPull(distance: Float) {
        val maximumPull = dp(DRAWER_OVERPULL_MAX_DP).toFloat()
        val resistanceLength = dp(DRAWER_OVERPULL_RESISTANCE_DP).toFloat()
        val resistedDistance = maximumPull * (1f - exp((-distance / resistanceLength).toDouble()).toFloat())
        binding.plannerCard.translationY = -resistedDistance
        updateCenterButtonPosition()
    }

    private fun springDrawerBack() {
        drawerSpring?.cancel()
        drawerSpring = SpringAnimation(
            binding.plannerCard,
            DynamicAnimation.TRANSLATION_Y,
            0f,
        ).apply {
            spring = SpringForce(0f).apply {
                dampingRatio = DRAWER_SPRING_DAMPING
                stiffness = DRAWER_SPRING_STIFFNESS
            }
            addUpdateListener { _, _, _ -> updateCenterButtonPosition() }
            start()
        }
    }

    private fun loadTourForEditing(reference: String) {
        editLoadInProgress = true
        binding.toolbar.setTitle(R.string.edit_tour)
        renderPlannerState()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stored = trackStore.listStoredTours().firstOrNull {
                        it.reference == reference && it.origin == TrackStore.StoredTourOrigin.IMPORTED
                    } ?: error(getString(R.string.only_planned_tours_editable))
                    val track = trackStore.loadStoredTrack(reference).asRouteDefinition()
                    val storedPoints = trackStore.loadRouteControlPoints(reference)
                    val controls = restoreSemanticControlPoints(track, storedPoints)
                    check(controls.size >= 2) { getString(R.string.route_needs_two_points) }
                    EditableTour(stored, track, controls)
                }
            }
            editLoadInProgress = false
            result.onSuccess { editable ->
                editTourReference = editable.stored.reference
                editTourName = editable.stored.name
                activityType = editable.track.activityType ?: editable.stored.activityType ?: activityType
                waypoints.clear()
                waypointNames.clear()
                editable.controlPoints.forEach { routePoint ->
                    waypoints += routePoint.point
                    routePoint.label?.let { waypointNames[routePoint.point] = it }
                }
                editable.controlPoints.filter { it.label.isNullOrBlank() }
                    .forEach { resolvePointName(it.point) }
                calculatedRoute = editable.track
                routeAlternatives = listOf(editable.track)
                selectedAlternativeIndex = 0
                editHistory.clear()
                editBaseline = EditBaseline(
                    waypoints = waypoints.toList(),
                    activityType = activityType,
                    routeMode = routeMode,
                    routeSignature = RouteVariantPolicy.signature(editable.track),
                )
                updateRouteSource()
                redrawMap()
                renderPlannerState()
                plannerSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                framePoints(editable.track.points)
            }.onFailure { error ->
                toast(
                    getString(
                        R.string.tour_open_error,
                        error.localizedMessage ?: getString(R.string.not_available),
                    ),
                )
                finish()
            }
        }
    }

    private fun loadRecordingRouteForEditing(sessionId: Long) {
        editLoadInProgress = true
        recordingSessionId = sessionId
        binding.toolbar.setTitle(R.string.edit_recording_route)
        renderPlannerState()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val session = trackStore.activeSession()
                        ?.takeIf { it.id == sessionId }
                        ?: error(getString(R.string.recording_no_longer_active))
                    val override = recordingRouteStore.load(sessionId)
                    val detour = detourStore.load(sessionId)
                    val route = detour?.route
                        ?: override?.route
                        ?: session.routeReference?.let(trackStore::loadStoredTrack)?.asRouteDefinition()
                    val storedControls = when {
                        detour != null -> emptyList()
                        override != null -> override.controlPoints
                        session.routeReference != null -> trackStore.loadRouteControlPoints(session.routeReference)
                        else -> emptyList()
                    }
                    val controls = route?.let { restoreSemanticControlPoints(it, storedControls) }.orEmpty()
                    RuntimeEditableRoute(session.activityType, route, controls)
                }
            }
            editLoadInProgress = false
            result.onSuccess { editable ->
                activityType = editable.activityType
                waypoints.clear()
                waypointNames.clear()
                editable.controlPoints.forEach { routePoint ->
                    waypoints += routePoint.point
                    routePoint.label?.let { waypointNames[routePoint.point] = it }
                }
                editable.controlPoints.filter { it.label.isNullOrBlank() }
                    .forEach { resolvePointName(it.point) }
                calculatedRoute = editable.route
                routeAlternatives = editable.route?.let(::listOf).orEmpty()
                selectedAlternativeIndex = 0
                editHistory.clear()
                editBaseline = EditBaseline(
                    waypoints = waypoints.toList(),
                    activityType = activityType,
                    routeMode = routeMode,
                    routeSignature = editable.route?.let(RouteVariantPolicy::signature),
                )
                if (editable.route != null) {
                    updateRouteSource()
                    redrawMap()
                    renderPlannerState()
                    plannerSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    framePoints(editable.route.points)
                } else {
                    initializeRecordingRouteStart()
                }
            }.onFailure { error ->
                toast(error.localizedMessage ?: getString(R.string.recording_no_longer_active))
                finish()
            }
        }
    }

    private fun initializeRecordingRouteStart() {
        val latitude = intent.getDoubleExtra(EXTRA_RECORDING_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_RECORDING_LONGITUDE, Double.NaN)
        if (latitude.isFinite() && longitude.isFinite()) {
            val start = TrackPoint(latitude, longitude)
            waypoints += start
            waypointNames[start] = getString(R.string.current_position)
            editBaseline = editBaseline?.copy(waypoints = waypoints.toList())
            redrawMap()
            renderPlannerState()
            plannerSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            binding.root.post { openPointSearch(PointRole.DESTINATION) }
        } else {
            renderPlannerState()
            binding.root.post { openPointSearch(PointRole.START) }
        }
    }

    private fun setupPlannerBottomSheet() {
        plannerDrawerController = BottomDrawerController(
            sheet = binding.plannerCard,
            content = binding.plannerContent,
            basePeekHeightPx = dp(PLANNER_DRAWER_PEEK_HEIGHT_DP),
            onSlide = { updateCenterButtonPosition() },
            onStableStateChanged = {
                // Material can settle an already-expanded sheet before applying a changed dynamic
                // height. Force one normal parent measure after the animation has finished.
                binding.plannerCard.requestLayout()
                binding.root.requestLayout()
                schedulePlannerExtentUpdate()
                updatePlannerScrollability()
                updateCenterButtonPosition()
                pendingFramePoints?.let { points ->
                    binding.mapView.post { framePoints(points) }
                }
            },
        )
        plannerSheetBehavior = plannerDrawerController.behavior
        binding.plannerContent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            schedulePlannerExtentUpdate()
        }
        binding.plannerScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePlannerScrollability()
        }
        binding.plannerLoadingOverlay.setOnTouchListener { _, _ ->
            binding.root.requestDisallowInterceptTouchEvent(true)
            true
        }
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            schedulePlannerExtentUpdate()
        }
        schedulePlannerExtentUpdate()
    }

    private fun schedulePlannerExtentUpdate() {
        if (drawerExtentUpdatePosted) return
        drawerExtentUpdatePosted = true
        binding.root.post {
            drawerExtentUpdatePosted = false
            updatePlannerExtent()
            updatePlannerScrollability()
            updateCenterButtonPosition()
        }
    }

    private fun updatePlannerExtent() {
        if (plannerDrawerOperationLocked) return
        val parentHeight = binding.root.height
        val contentHeight = binding.drawerCompactHeader.height + naturalPlannerContentHeight()
        if (parentHeight <= 0 || contentHeight <= 0) return
        val maximumVisibleHeight = (parentHeight - binding.toolbar.bottom)
            .coerceAtLeast(plannerSheetBehavior.peekHeight)
        val visibleHeight = contentHeight.coerceIn(
            plannerSheetBehavior.peekHeight,
            maximumVisibleHeight,
        )
        if (binding.plannerCard.layoutParams.height != visibleHeight) {
            binding.plannerCard.updateLayoutParams { height = visibleHeight }
        }
        binding.plannerCard.doOnLayout { card ->
            if (plannerSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                val targetTop = (binding.root.height - card.height)
                    .coerceAtLeast(binding.toolbar.bottom)
                val offset = targetTop - card.top
                if (offset != 0) ViewCompat.offsetTopAndBottom(card, offset)
            }
        }
    }

    private fun naturalPlannerContentHeight(): Int {
        val content = binding.plannerContent
        val lastVisibleChildBottom = (0 until content.childCount)
            .asSequence()
            .map(content::getChildAt)
            .filter { it.visibility != View.GONE }
            .maxOfOrNull { it.bottom }
            ?: 0
        return if (lastVisibleChildBottom > 0) {
            lastVisibleChildBottom + content.paddingBottom
        } else {
            content.height
        }
    }

    private fun updatePlannerScrollability() {
        if (plannerDrawerOperationLocked) {
            binding.plannerScroll.contentScrollingEnabled = false
            return
        }
        val contentOverflows = binding.plannerScroll.height > 0 &&
            naturalPlannerContentHeight() > binding.plannerScroll.height
        val maximumVisibleHeight = (binding.root.height - binding.toolbar.bottom)
            .coerceAtLeast(plannerSheetBehavior.peekHeight)
        val drawerIsFullyExtended = binding.plannerCard.height >= maximumVisibleHeight - 1
        binding.plannerScroll.contentScrollingEnabled =
            plannerSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED &&
            drawerIsFullyExtended &&
            contentOverflows
    }

    private fun setupMap() {
        binding.mapView.getMapAsync { readyMap ->
            map = readyMap.apply {
                uiSettings.isCompassEnabled = true
                uiSettings.isAttributionEnabled = true
                uiSettings.isLogoEnabled = true
                addOnMapClickListener(::handleMapClick)
                setOnMarkerClickListener(::handleMarkerClick)
            }
            readyMap.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                MapStyleLocalizer.localize(style, AppLanguage.forContext(this))
                mapStyle = style
                style.addSource(GeoJsonSource(ROUTE_SOURCE, EMPTY_FEATURE_COLLECTION))
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(Color.parseColor("#1677FF")),
                        lineWidth(6f),
                        lineOpacity(0.9f),
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
                redrawMap()
                val pointsToFrame = pendingFramePoints
                if (!pointsToFrame.isNullOrEmpty()) {
                    framePoints(pointsToFrame)
                } else if (waypoints.isEmpty()) {
                    readyMap.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, DEFAULT_ZOOM))
                }
            }
        }
    }

    private fun handleMapClick(position: LatLng): Boolean {
        val point = TrackPoint(position.latitude, position.longitude)
        pendingMapRole?.let { role ->
            val viaIndex = pendingMapViaIndex
            pendingMapRole = null
            pendingMapViaIndex = null
            applyPoint(role, point, targetViaIndex = viaIndex)
            return true
        }
        when (waypoints.size) {
            0 -> applyPoint(PointRole.START, point)
            1 -> applyPoint(PointRole.DESTINATION, point)
            else -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.planner_add_point_title)
                .setItems(
                    arrayOf(
                        getString(R.string.planner_add_via),
                        getString(R.string.planner_add_destination),
                    ),
                ) { _, which ->
                    mutateWaypoints {
                        if (which == 0) add(lastIndex, point) else add(point)
                    }
                    resolvePointName(point)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        return true
    }

    private fun handleMarkerClick(marker: Marker): Boolean {
        val index = waypointMarkers.indexOf(marker)
        if (index < 0 || routingInProgress) return false
        val label = waypointLabel(index)
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage(R.string.planner_remove_point_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                mutateWaypoints(preserveExpandedEditor = true) { removeAt(index) }
            }
            .show()
        return true
    }

    private fun mutateWaypoints(
        preserveExpandedEditor: Boolean = false,
        change: MutableList<TrackPoint>.() -> Unit,
    ) {
        pushUndoState()
        waypoints.change()
        preserveExpandedWaypointEditorAfterRouting =
            preserveExpandedEditor && waypoints.size >= 2
        if (preserveExpandedEditor) waypointEditorExpanded = true
        invalidateCalculatedRoute()
        redrawMap()
        renderPlannerState()
        revealPlannerAfterStructureChange()
        framePoints(waypoints)
        scheduleAutomaticRouting()
    }

    private fun applyPoint(
        role: PointRole,
        point: TrackPoint,
        name: String? = null,
        targetViaIndex: Int? = editingViaIndex,
    ) {
        if (role == PointRole.DESTINATION && waypoints.isEmpty()) {
            pushUndoState()
            destinationDraft = point
            name?.let { waypointNames[point] = it }
            invalidateCalculatedRoute()
            redrawMap()
            renderPlannerState()
            framePoints(listOf(point))
            if (name == null) resolvePointName(point)
            return
        }
        val draftedDestination = destinationDraft
        mutateWaypoints {
            when (role) {
                PointRole.START -> if (isEmpty()) {
                    add(point)
                    draftedDestination?.let(::add)
                } else {
                    this[0] = point
                }
                PointRole.DESTINATION -> when (size) {
                    0 -> add(point)
                    1 -> add(point)
                    else -> this[lastIndex] = point
                }
                PointRole.VIA -> if (targetViaIndex != null && targetViaIndex in 1 until lastIndex) {
                    this[targetViaIndex] = point
                } else if (size >= 2) {
                    add(lastIndex, point)
                }
            }
        }
        if (role == PointRole.START && draftedDestination != null) destinationDraft = null
        name?.let { waypointNames[point] = it }
        renderPlannerState()
        if (name == null) resolvePointName(point)
    }

    private fun reverseRouteDirection() {
        if (isBusy()) return
        if (waypoints.size < 2) {
            val singleStart = waypoints.singleOrNull()
            val singleDestination = destinationDraft
            if (singleStart == null && singleDestination == null) return
            pushUndoState()
            if (singleStart != null) {
                waypoints.clear()
                destinationDraft = singleStart
            } else if (singleDestination != null) {
                waypoints += singleDestination
                destinationDraft = null
            }
            invalidateCalculatedRoute()
            redrawMap()
            renderPlannerState()
            framePoints(waypoints + listOfNotNull(destinationDraft))
            return
        }
        val existingRoute = calculatedRoute
        if (existingRoute != null && RouteVariantPolicy.isClosed(existingRoute)) {
            autoRoutingJob?.cancel()
            autoRoutingJob = null
            pushUndoState()
            val reversedRoute = RouteVariantPolicy.reversed(existingRoute)
            calculatedRoute = reversedRoute
            routeAlternatives = listOf(reversedRoute)
            selectedAlternativeIndex = 0
            roundTripPhase = RoundTripPhase.NONE
            redrawMap()
            renderPlannerState()
            framePoints(reversedRoute.points)
            return
        }
        if (editTourReference != null && existingRoute != null) {
            autoRoutingJob?.cancel()
            autoRoutingJob = null
            pushUndoState()
            waypoints.reverse()
            val reversedRoute = RouteVariantPolicy.reversed(existingRoute)
            calculatedRoute = reversedRoute
            routeAlternatives = listOf(reversedRoute)
            selectedAlternativeIndex = 0
            roundTripPhase = RoundTripPhase.NONE
            redrawMap()
            renderPlannerState()
            framePoints(reversedRoute.points)
            return
        }
        recalculateReversedRoute()
    }

    private fun recalculateReversedRoute() {
        val reversedWaypoints = waypoints.asReversed().toList()
        val selectedRouteMode = routeMode
        lockPlannerDrawerForOperation()
        routingInProgress = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    calculateRouteAlternatives(reversedWaypoints, selectedRouteMode)
                }
            }
            result.onSuccess { routes ->
                pushUndoState()
                waypoints.clear()
                waypoints.addAll(reversedWaypoints)
                routingInProgress = false
                roundTripPhase = if (selectedRouteMode == RouteMode.ROUND_TRIP) {
                    RoundTripPhase.OUTBOUND
                } else {
                    RoundTripPhase.NONE
                }
                routeAlternatives = routes
                selectedAlternativeIndex = 0
                calculatedRoute = routes.first()
                redrawMap()
                renderPlannerState()
                finishLockedPlannerOperationAfterMapUpdate(routes.first().points)
            }.onFailure { error ->
                routingInProgress = false
                renderPlannerState()
                unlockPlannerDrawerAfterOperation()
                toast(
                    getString(
                        R.string.route_calculation_error,
                        error.localizedMessage ?: getString(R.string.not_available),
                    ),
                )
            }
        }
    }

    private fun lockPlannerDrawerForOperation() {
        if (plannerDrawerOperationLocked) return
        drawerSpring?.cancel()
        binding.plannerCard.translationY = 0f
        plannerDrawerOperationLocked = true
        binding.plannerScroll.contentScrollingEnabled = false
        binding.plannerLoadingOverlay.visibility = View.VISIBLE
        binding.toolbar.menu.findItem(R.id.action_save_route)?.isEnabled = false
        binding.toolbar.menu.findItem(R.id.action_undo_route_edit)?.isEnabled = false
        updateCenterButtonPosition()
    }

    private fun finishLockedPlannerOperationAfterMapUpdate(points: List<TrackPoint>) {
        binding.mapView.post {
            framePoints(points)
            binding.mapView.postDelayed(
                ::unlockPlannerDrawerAfterOperation,
                ROUTE_FRAME_ANIMATION_MS + ROUTE_FRAME_SETTLE_BUFFER_MS,
            )
        }
    }

    private fun unlockPlannerDrawerAfterOperation() {
        if (!plannerDrawerOperationLocked) return
        plannerDrawerOperationLocked = false
        binding.plannerLoadingOverlay.visibility = View.INVISIBLE
        schedulePlannerExtentUpdate()
        binding.root.post(::updateCenterButtonPosition)
    }

    private fun setupWaypointList() {
        waypointAdapter = WaypointAdapter()
        binding.waypointList.apply {
            layoutManager = LinearLayoutManager(this@RoutePlannerActivity)
            adapter = waypointAdapter
            isNestedScrollingEnabled = false
        }
        waypointTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (!isBusy() && waypointEditorExpanded && waypoints.size >= 2) {
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            } else {
                0
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in waypoints.indices || to !in waypoints.indices || from == to || isBusy()) {
                    return false
                }
                if (!WaypointOrdering.move(waypoints, from, to)) return false
                waypointAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    waypointDragStart = waypoints.toList()
                    viewHolder.itemView.alpha = 0.82f
                    viewHolder.itemView.scaleX = 1.02f
                    viewHolder.itemView.scaleY = 1.02f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
                val original = waypointDragStart
                waypointDragStart = null
                recyclerView.post {
                    if (original != null && original != waypoints) commitWaypointReorder(original)
                    else waypointAdapter.notifyDataSetChanged()
                }
            }
        }).also { it.attachToRecyclerView(binding.waypointList) }
    }

    private fun commitWaypointReorder(original: List<TrackPoint>) {
        pushUndoState(waypointsOverride = original)
        invalidateCalculatedRoute()
        redrawMap()
        renderPlannerState()
        revealPlannerAfterStructureChange()
        framePoints(waypoints)
        scheduleAutomaticRouting()
    }

    private fun toggleWaypointEditor() {
        if (waypoints.size < MIN_COLLAPSIBLE_WAYPOINT_COUNT) return
        waypointEditorExpanded = !waypointEditorExpanded
        renderWaypointEditorVisibility()
        schedulePlannerExtentUpdate()
        binding.plannerScroll.post {
            updateCenterButtonPosition()
            binding.waypointList.scrollToPosition(0)
        }
    }

    private fun renderWaypointEditorVisibility() {
        val canCollapse = waypoints.size >= MIN_COLLAPSIBLE_WAYPOINT_COUNT
        if (!canCollapse) waypointEditorExpanded = true
        binding.pointEditor.visibility = View.VISIBLE
        binding.waypointList.updateLayoutParams {
            height = dp(displayedWaypointRowCount() * WAYPOINT_ROW_HEIGHT_DP)
        }
        waypointAdapter.notifyDataSetChanged()
    }

    private fun displayedWaypointRowCount(): Int =
        if (waypointEditorExpanded || waypoints.size < MIN_COLLAPSIBLE_WAYPOINT_COUNT) {
            waypoints.size
        } else {
            COLLAPSED_WAYPOINT_ROW_COUNT
        }

    private fun isCollapsedWaypointSummary(adapterPosition: Int): Boolean =
        !waypointEditorExpanded &&
            waypoints.size >= MIN_COLLAPSIBLE_WAYPOINT_COUNT &&
            adapterPosition == COLLAPSED_WAYPOINT_SUMMARY_POSITION

    private fun waypointIndexForAdapterPosition(adapterPosition: Int): Int? =
        if (waypointEditorExpanded || waypoints.size < MIN_COLLAPSIBLE_WAYPOINT_COUNT) {
            adapterPosition.takeIf { it in waypoints.indices }
        } else {
            when (adapterPosition) {
                0 -> 0
                COLLAPSED_WAYPOINT_DESTINATION_POSITION -> waypoints.lastIndex
                else -> null
            }
        }

    private fun openWaypoint(index: Int) {
        if (index !in waypoints.indices || isBusy()) return
        when (index) {
            0 -> openPointSearch(PointRole.START)
            waypoints.lastIndex -> openPointSearch(PointRole.DESTINATION)
            else -> openPointSearch(PointRole.VIA, index)
        }
    }

    private fun setupPointSearch() {
        binding.closePointSearchButton.setOnClickListener { closePointSearch() }
        binding.clearPointSearchButton.setOnClickListener {
            if (binding.placeSearchInput.text.isNullOrEmpty()) closePointSearch()
            else binding.placeSearchInput.text?.clear()
        }
        binding.useCurrentPositionButton.setOnClickListener {
            pointSearchRole?.let { role ->
                val viaIndex = editingViaIndex
                closePointSearch()
                requestLocationForRole(role, viaIndex)
            }
        }
        binding.useHomeButton.setOnClickListener { pointSearchRole?.let(::useHomeForRole) }
        binding.selectPointOnMapButton.setOnClickListener {
            pointSearchRole?.let { role ->
                val viaIndex = editingViaIndex
                closePointSearch()
                pendingMapRole = role
                pendingMapViaIndex = viaIndex
                renderPlannerState()
            }
        }
        binding.deletePointButton.setOnClickListener {
            pointSearchRole?.let(::deletePointForRole)
        }
        binding.placeSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                scheduleLivePlaceSearch(value?.toString().orEmpty())
            }
        })
        binding.placeSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                scheduleLivePlaceSearch(binding.placeSearchInput.text?.toString().orEmpty(), immediate = true)
                true
            } else {
                false
            }
        }
    }

    private fun openPointSearch(role: PointRole, viaIndex: Int? = null) {
        if (routingInProgress) return
        pointSearchRole = role
        editingViaIndex = viaIndex.takeIf { role == PointRole.VIA }
        val canDelete = pointIndex(role) != null ||
            (role == PointRole.DESTINATION && destinationDraft != null)
        binding.deletePointButton.visibility = if (canDelete) View.VISIBLE else View.GONE
        // Keep the hidden drawer measured while search is in front. Otherwise a changed simple
        // draft can return with the old collapsed height even though the behavior says expanded.
        binding.plannerCard.visibility = View.INVISIBLE
        binding.centerButton.visibility = View.GONE
        binding.pointSearchOverlay.visibility = View.VISIBLE
        binding.placeSearchInput.text?.clear()
        binding.placeSearchResults.removeAllViews()
        binding.placeSearchMessage.setText(R.string.place_search_live_hint)
        binding.placeSearchMessage.visibility = View.VISIBLE
        binding.placeSearchInput.requestFocus()
        binding.placeSearchInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.placeSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closePointSearch() {
        pointSearchJob?.cancel()
        pointSearchJob = null
        pointSearchGeneration++
        searchInProgress = false
        pointSearchRole = null
        editingViaIndex = null
        binding.placeSearchProgress.visibility = View.GONE
        binding.pointSearchOverlay.visibility = View.GONE
        binding.plannerCard.visibility = View.VISIBLE
        binding.centerButton.visibility = View.VISIBLE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.placeSearchInput.windowToken, 0)
        renderPlannerState()
        binding.plannerContent.requestLayout()
        binding.plannerContent.doOnLayout {
            if (definedPlanningPointCount() <= SIMPLE_DRAFT_MAX_POINT_COUNT) {
                drawerSpring?.cancel()
                binding.plannerCard.translationY = 0f
                updatePlannerExtent()
                plannerDrawerController.expand()
            }
            binding.plannerCard.doOnLayout { updateCenterButtonPosition() }
        }
    }

    private fun definedPlanningPointCount(): Int =
        waypoints.size + if (destinationDraft != null) 1 else 0

    private fun scheduleLivePlaceSearch(rawQuery: String, immediate: Boolean = false) {
        val query = rawQuery.trim()
        pointSearchJob?.cancel()
        val generation = ++pointSearchGeneration
        if (query.length < 3) {
            searchInProgress = false
            binding.placeSearchProgress.visibility = View.GONE
            binding.placeSearchResults.removeAllViews()
            binding.placeSearchMessage.setText(R.string.place_search_live_hint)
            binding.placeSearchMessage.visibility = View.VISIBLE
            renderPlannerState()
            return
        }
        pointSearchJob = lifecycleScope.launch {
            if (!immediate) delay(450)
            if (generation != pointSearchGeneration || pointSearchRole == null) return@launch
            searchInProgress = true
            binding.placeSearchProgress.visibility = View.VISIBLE
            binding.placeSearchMessage.setText(R.string.searching_place)
            binding.placeSearchMessage.visibility = View.VISIBLE
            renderPlannerState()
            val reference = placeSearchReferencePoint()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PlaceSearchRanker.rank(
                        query = query,
                        results = placeSearchClient.search(query, reference),
                        reference = reference,
                    )
                }
            }
            if (generation != pointSearchGeneration || pointSearchRole == null) return@launch
            searchInProgress = false
            binding.placeSearchProgress.visibility = View.GONE
            renderPlannerState()
            result.onSuccess(::renderPlaceSearchResults).onFailure { error ->
                binding.placeSearchResults.removeAllViews()
                binding.placeSearchMessage.text = getString(
                    R.string.place_search_error,
                    error.localizedMessage ?: getString(R.string.not_available),
                )
                binding.placeSearchMessage.visibility = View.VISIBLE
            }
        }
    }

    private fun renderPlaceSearchResults(places: List<PlaceSearchResult>) {
        binding.placeSearchResults.removeAllViews()
        binding.placeSearchMessage.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
        if (places.isEmpty()) binding.placeSearchMessage.setText(R.string.place_search_no_results)
        places.forEach { place ->
            val parts = place.displayName.split(',', limit = 2)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                isClickable = true
                isFocusable = true
                val selectable = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                setBackgroundResource(selectable.resourceId)
                setOnClickListener {
                    val role = pointSearchRole ?: return@setOnClickListener
                    applyPoint(
                        role,
                        TrackPoint(place.latitude, place.longitude),
                        shortPlaceLabel(place.displayName),
                    )
                    closePointSearch()
                }
            }
            row.addView(TextView(this).apply {
                text = parts.first()
                setTextColor(ContextCompat.getColor(this@RoutePlannerActivity, R.color.forest_900))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            if (parts.size > 1) {
                row.addView(TextView(this).apply {
                    text = parts[1].trim()
                    setTextColor(ContextCompat.getColor(this@RoutePlannerActivity, R.color.forest_700))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            binding.placeSearchResults.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            binding.placeSearchResults.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@RoutePlannerActivity, R.color.moss_300))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    private fun placeSearchReferencePoint(): TrackPoint? {
        val routeReference = when (pointSearchRole) {
            PointRole.START -> destinationDraft
                ?: waypoints.lastOrNull()?.takeIf { waypoints.size >= 2 }
            PointRole.DESTINATION -> waypoints.firstOrNull()
            PointRole.VIA -> editingViaIndex
                ?.let { index -> waypoints.getOrNull(index - 1) }
                ?: waypoints.dropLast(1).lastOrNull()
                ?: waypoints.firstOrNull()
            null -> null
        }
        return routeReference ?: initialSearchReference ?: recentUserSearchReference()
    }

    private fun intentSearchReference(): TrackPoint? {
        if (
            !intent.hasExtra(EXTRA_SEARCH_REFERENCE_LATITUDE) ||
            !intent.hasExtra(EXTRA_SEARCH_REFERENCE_LONGITUDE)
        ) return null
        val latitude = intent.getDoubleExtra(EXTRA_SEARCH_REFERENCE_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_SEARCH_REFERENCE_LONGITUDE, Double.NaN)
        return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            TrackPoint(latitude, longitude)
        } else {
            null
        }
    }

    private fun recentUserSearchReference(): TrackPoint? {
        if (!hasLocationPermission()) return null
        return findRecentLastKnownLocation()?.let { location ->
            TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            )
        }
    }

    private fun useHomeForRole(role: PointRole) {
        val preferences = getSharedPreferences(HOME_PREFERENCES, MODE_PRIVATE)
        val latitude = preferences.getString(HOME_LATITUDE, null)?.toDoubleOrNull()
        val longitude = preferences.getString(HOME_LONGITUDE, null)?.toDoubleOrNull()
        if (latitude != null && longitude != null) {
            applyPoint(role, TrackPoint(latitude, longitude), getString(R.string.home_location))
            closePointSearch()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_location_missing_title)
            .setMessage(R.string.home_location_missing_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_current_as_home) { _, _ ->
                pendingHomeSave = true
                val viaIndex = editingViaIndex
                closePointSearch()
                requestLocationForRole(role, viaIndex)
            }
            .show()
    }

    private fun pointIndex(role: PointRole): Int? = when (role) {
        PointRole.START -> 0.takeIf { waypoints.isNotEmpty() }
        PointRole.DESTINATION -> waypoints.lastIndex.takeIf { waypoints.size >= 2 }
        PointRole.VIA -> editingViaIndex?.takeIf { it in 1 until waypoints.lastIndex }
    }

    private fun deletePointForRole(role: PointRole) {
        if (role == PointRole.DESTINATION && waypoints.isEmpty() && destinationDraft != null) {
            closePointSearch()
            destinationDraft = null
            invalidateCalculatedRoute()
            redrawMap()
            renderPlannerState()
            return
        }
        val index = pointIndex(role) ?: return
        closePointSearch()
        mutateWaypoints(preserveExpandedEditor = true) { removeAt(index) }
    }

    private fun shortPlaceLabel(displayName: String): String =
        displayName.split(',').take(2).joinToString(", ").trim()

    private fun resolvePointName(point: TrackPoint) {
        if (waypointNames[point] != null) return
        lifecycleScope.launch {
            val place = runCatching {
                withContext(Dispatchers.IO) {
                    placeSearchClient.reverse(point.latitude, point.longitude)
                }
            }.getOrNull() ?: return@launch
            if (point != destinationDraft && point !in waypoints) return@launch
            waypointNames[point] = shortPlaceLabel(place.displayName)
            renderPlannerState()
        }
    }

    private fun coordinateLabel(point: TrackPoint): String = String.format(
        Locale.US,
        "%.5f, %.5f",
        point.latitude,
        point.longitude,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun undoEdit() {
        val previous = editHistory.removeLastOrNull() ?: return
        autoRoutingJob?.cancel()
        autoRoutingJob = null
        waypoints.clear()
        waypoints.addAll(previous.waypoints)
        waypointNames.clear()
        waypointNames.putAll(previous.waypointNames)
        destinationDraft = previous.destinationDraft
        calculatedRoute = previous.calculatedRoute
        routeAlternatives = previous.routeAlternatives
        selectedAlternativeIndex = previous.selectedAlternativeIndex
        roundTripPhase = previous.roundTripPhase
        activityType = previous.activityType
        routeMode = previous.routeMode
        redrawMap()
        renderPlannerState()
        framePoints(calculatedRoute?.points ?: (waypoints + listOfNotNull(destinationDraft)))
    }

    private fun pushUndoState(waypointsOverride: List<TrackPoint>? = null) {
        editHistory += PlannerUndoState(
            waypoints = waypointsOverride?.toList() ?: waypoints.toList(),
            waypointNames = waypointNames.toMap(),
            destinationDraft = destinationDraft,
            calculatedRoute = calculatedRoute,
            routeAlternatives = routeAlternatives.toList(),
            selectedAlternativeIndex = selectedAlternativeIndex,
            roundTripPhase = roundTripPhase,
            activityType = activityType,
            routeMode = routeMode,
        )
    }

    private fun attemptExitPlanner() {
        if (plannerDrawerOperationLocked) return
        val prompt = when {
            recordingSessionId != null -> {
                if (!hasUnsavedEditChanges()) {
                    finish()
                    return
                }
                Pair(
                    R.string.discard_navigation_changes_title,
                    R.string.discard_navigation_changes_message,
                )
            }
            editTourReference != null && hasUnsavedEditChanges() -> Pair(
                R.string.discard_route_changes_title,
                R.string.discard_route_changes_message,
            )
            editTourReference == null && hasNewRouteDraft() -> Pair(
                R.string.discard_new_route_title,
                R.string.discard_new_route_message,
            )
            else -> {
                finish()
                return
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(prompt.first)
            .setMessage(prompt.second)
            .setNegativeButton(R.string.keep_editing, null)
        if (canSaveCurrentRoute()) {
            dialog
                .setNeutralButton(R.string.discard) { _, _ -> finish() }
                .setPositiveButton(
                    when {
                        recordingSessionId != null -> R.string.use_navigation_route
                        editTourReference == null -> R.string.save_route
                        else -> R.string.save_changes
                    },
                ) { _, _ -> askForRouteName(returnToTourLibraryAfterSave = true) }
        } else {
            dialog.setPositiveButton(R.string.discard) { _, _ -> finish() }
        }
        dialog.show()
    }

    private fun hasNewRouteDraft(): Boolean =
        waypoints.isNotEmpty() || destinationDraft != null

    private fun hasUnsavedEditChanges(): Boolean {
        val baseline = editBaseline ?: return false
        if (waypoints != baseline.waypoints) return true
        if (destinationDraft != null) return true
        if (activityType != baseline.activityType || routeMode != baseline.routeMode) return true
        return calculatedRoute?.let(RouteVariantPolicy::signature) != baseline.routeSignature
    }

    private fun canSaveCurrentRoute(): Boolean =
        calculatedRoute != null &&
            !isBusy() &&
            pendingMapRole == null &&
            roundTripPhase != RoundTripPhase.OUTBOUND &&
            roundTripPhase != RoundTripPhase.RETURN &&
            when {
                recordingSessionId != null -> hasUnsavedEditChanges()
                editTourReference == null -> true
                else -> hasUnsavedEditChanges()
            }

    private fun invalidateCalculatedRoute() {
        calculatedRoute = null
        routeAlternatives = emptyList()
        selectedAlternativeIndex = 0
        roundTripPhase = RoundTripPhase.NONE
        updateRouteSource()
    }

    private fun redrawMap() {
        val readyMap = map ?: return
        waypointMarkers.forEach(readyMap::removeMarker)
        waypointMarkers.clear()
        waypoints.forEachIndexed { index, point ->
            waypointMarkers += readyMap.addMarker(
                MarkerOptions()
                    .position(LatLng(point.latitude, point.longitude))
                    .title(waypointLabel(index))
                    .icon(createWaypointIcon(index)),
            )
        }
        destinationDraft?.let { point ->
            waypointMarkers += readyMap.addMarker(
                MarkerOptions()
                    .position(LatLng(point.latitude, point.longitude))
                    .title(getString(R.string.planner_destination_marker))
                    .icon(createWaypointIcon("Z", R.color.warning)),
            )
        }
        updateRouteSource()
    }

    private fun createWaypointIcon(index: Int): Icon {
        val label = when (index) {
            0 -> "S"
            waypoints.lastIndex -> "Z"
            else -> index.toString()
        }
        val color = if (index == waypoints.lastIndex && waypoints.size > 1) {
            R.color.warning
        } else {
            R.color.forest_700
        }
        return createWaypointIcon(label, color)
    }

    private fun createWaypointIcon(label: String, colorRes: Int): Icon {
        val density = resources.displayMetrics.density
        val size = (34 * density).toInt().coerceAtLeast(34)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@RoutePlannerActivity, colorRes)
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.45f, paint)
        paint.apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.46f
            typeface = Typeface.DEFAULT_BOLD
        }
        val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, size / 2f, baseline, paint)
        return IconFactory.getInstance(this).fromBitmap(bitmap)
    }

    private fun waypointLabel(index: Int): String = when (index) {
        0 -> getString(R.string.planner_start_marker)
        waypoints.lastIndex -> getString(R.string.planner_destination_marker)
        else -> getString(R.string.planner_via_marker, index)
    }

    private fun updateRouteSource() {
        val source = mapStyle?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE) ?: return
        val features = calculatedRoute?.segments.orEmpty().mapNotNull { segment ->
            if (segment.size < 2) null else Feature.fromGeometry(
                LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun chooseActivityType() {
        val types = ActivityType.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_activity_type)
            .setSingleChoiceItems(
                types.map { getString(it.labelRes()) }.toTypedArray(),
                types.indexOf(activityType),
            ) { dialog, which ->
                if (activityType != types[which]) {
                    pushUndoState()
                    activityType = types[which]
                    invalidateCalculatedRoute()
                    renderPlannerState()
                    scheduleAutomaticRouting()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseRouteMode() {
        val modes = RouteMode.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_route_mode)
            .setSingleChoiceItems(
                modes.map { getString(it.labelRes) }.toTypedArray(),
                modes.indexOf(routeMode),
            ) { dialog, which ->
                if (routeMode != modes[which]) {
                    pushUndoState()
                    routeMode = modes[which]
                    invalidateCalculatedRoute()
                    renderPlannerState()
                    scheduleAutomaticRouting()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun calculateRoute() {
        if (waypoints.size < 2 || routingInProgress) return
        routingInProgress = true
        renderPlannerState()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    calculateRouteAlternatives()
                }
            }
            routingInProgress = false
            result.onSuccess { routes ->
                roundTripPhase = if (routeMode == RouteMode.ROUND_TRIP) {
                    RoundTripPhase.OUTBOUND
                } else {
                    RoundTripPhase.NONE
                }
                routeAlternatives = routes
                selectedAlternativeIndex = 0
                calculatedRoute = routes.first()
                updateRouteSource()
                enterRouteSelectionMode(routes)
                renderPlannerState()
                framePoints(routes.first().points)
            }.onFailure { error ->
                renderPlannerState()
                toast(
                    getString(
                        R.string.route_calculation_error,
                        error.localizedMessage ?: getString(R.string.not_available),
                    ),
                )
            }
        }
    }

    private fun scheduleAutomaticRouting() {
        autoRoutingJob?.cancel()
        if (waypoints.size < 2) return
        autoRoutingJob = lifecycleScope.launch {
            delay(AUTO_ROUTE_DEBOUNCE_MS)
            if (waypoints.size >= 2 && calculatedRoute == null && !isBusy()) {
                calculateRoute()
            }
        }
    }

    private fun calculateRouteAlternatives(
        selectedWaypoints: List<TrackPoint> = waypoints.toList(),
        selectedRouteMode: RouteMode = routeMode,
    ): List<GpxTrack> {
        val routes = when (selectedRouteMode) {
            RouteMode.ONE_WAY -> calculateAvailableAlternatives(
                selectedWaypoints,
                ALTERNATIVE_INDICES,
            )
            RouteMode.OUT_AND_BACK -> calculateAvailableAlternatives(
                selectedWaypoints,
                ALTERNATIVE_INDICES,
            ).map(RouteVariantPolicy::asOutAndBack)
            RouteMode.ROUND_TRIP -> calculateAvailableAlternatives(
                selectedWaypoints,
                ALTERNATIVE_INDICES,
            )
        }
        return routes.distinctBy(RouteVariantPolicy::signature).ifEmpty {
            error(getString(R.string.no_route_alternative_found))
        }
    }

    private fun calculateAvailableAlternatives(
        requestWaypoints: List<TrackPoint>,
        alternativeIndices: IntRange,
        noGoPoints: List<RoutingNoGoPoint> = emptyList(),
    ): List<GpxTrack> {
        var lastError: Throwable? = null
        val routes = buildList {
            alternativeIndices.forEach { alternativeIndex ->
                runCatching {
                    routingClient.calculate(
                        requestWaypoints,
                        activityType,
                        routeName = getString(R.string.planned_route_name),
                        alternativeIndex = alternativeIndex,
                        noGoPoints = noGoPoints,
                    )
                }.onSuccess(::add).onFailure { lastError = it }
            }
        }
        if (routes.isEmpty()) throw lastError ?: IllegalStateException(getString(R.string.no_route_found))
        return routes
    }

    private fun calculateRoundTripReturns() {
        val outbound = calculatedRoute ?: return
        if (routingInProgress || waypoints.size < 2) return
        routingInProgress = true
        renderPlannerState()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { calculateReturnAlternatives(outbound) }
            }
            routingInProgress = false
            result.onSuccess { loops ->
                roundTripPhase = RoundTripPhase.RETURN
                routeAlternatives = loops
                selectedAlternativeIndex = 0
                calculatedRoute = loops.first()
                updateRouteSource()
                enterRouteSelectionMode(loops)
                renderPlannerState()
                framePoints(loops.first().points)
            }.onFailure { error ->
                renderPlannerState()
                toast(
                    getString(
                        R.string.route_calculation_error,
                        error.localizedMessage ?: getString(R.string.not_available),
                    ),
                )
            }
        }
    }

    private fun calculateReturnAlternatives(outbound: GpxTrack): List<GpxTrack> {
        val destination = waypoints.last()
        val returnWaypoints = listOf(destination, waypoints.first())
        val noGoPoints = sampleNoGoCorridor(outbound)
        val avoidedReturns = runCatching {
            calculateAvailableAlternatives(returnWaypoints, ALTERNATIVE_INDICES, noGoPoints)
        }.getOrDefault(emptyList())
        val avoidedLoops = combineGenuineLoops(outbound, avoidedReturns, destination)
        if (avoidedLoops.isNotEmpty()) return avoidedLoops

        val fallbackReturns = calculateAvailableAlternatives(returnWaypoints, ALTERNATIVE_INDICES)
        return combineGenuineLoops(outbound, fallbackReturns, destination).ifEmpty {
            error(getString(R.string.no_route_alternative_found))
        }
    }

    private fun confirmRoundTripReturn() {
        if (routeMode != RouteMode.ROUND_TRIP || roundTripPhase != RoundTripPhase.RETURN) return
        if (calculatedRoute == null || routingInProgress) return
        roundTripPhase = RoundTripPhase.COMPLETE
        renderPlannerState()
    }

    private fun combineGenuineLoops(
        outbound: GpxTrack,
        inboundRoutes: List<GpxTrack>,
        destination: TrackPoint,
    ): List<GpxTrack> = inboundRoutes
        .map { inbound -> RouteVariantPolicy.combineAsLoop(outbound, inbound) }
        .filter { loop -> RouteVariantPolicy.isGenuineLoop(loop, destination) }
        .distinctBy(RouteVariantPolicy::signature)

    private fun sampleNoGoCorridor(route: GpxTrack): List<RoutingNoGoPoint> {
        val points = route.points
        if (points.size < 3) return emptyList()
        val distances = MutableList(points.size) { 0.0 }
        for (index in 1 until points.size) {
            distances[index] = distances[index - 1] + GeoMath.distanceMeters(points[index - 1], points[index])
        }
        val totalDistance = distances.last()
        val endpointClearance = minOf(300.0, totalDistance * 0.18)
        val usableDistance = totalDistance - endpointClearance * 2.0
        if (usableDistance <= 0.0) return emptyList()
        val spacing = maxOf(110.0, usableDistance / MAX_NO_GO_POINTS)
        var nextDistance = endpointClearance
        return buildList {
            for (index in 1 until points.lastIndex) {
                val distance = distances[index]
                if (distance > totalDistance - endpointClearance) break
                if (distance >= nextDistance) {
                    add(RoutingNoGoPoint(points[index], NO_GO_RADIUS_METERS))
                    nextDistance = distance + spacing
                }
            }
        }.take(MAX_NO_GO_POINTS)
    }

    private fun selectAlternative(offset: Int) {
        if (routeAlternatives.size < 2) return
        selectedAlternativeIndex =
            (selectedAlternativeIndex + offset + routeAlternatives.size) % routeAlternatives.size
        calculatedRoute = routeAlternatives[selectedAlternativeIndex]
        if (roundTripPhase == RoundTripPhase.COMPLETE) {
            roundTripPhase = RoundTripPhase.RETURN
        }
        updateRouteSource()
        renderPlannerState()
        framePoints(calculatedRoute?.points.orEmpty())
    }

    private fun enterRouteSelectionMode(routes: List<GpxTrack>) {
        val preserveExpandedEditor = preserveExpandedWaypointEditorAfterRouting
        preserveExpandedWaypointEditorAfterRouting = false
        if (routes.size < 2) return
        if (preserveExpandedEditor) waypointEditorExpanded = true
        plannerSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        binding.plannerScroll.post {
            binding.plannerScroll.smoothScrollTo(0, 0)
            updateCenterButtonPosition()
        }
    }

    private fun askForRouteName(returnToTourLibraryAfterSave: Boolean = false) {
        val route = calculatedRoute ?: return
        if (recordingSessionId != null) {
            saveRoute(route)
            return
        }
        val isNewTour = editTourReference == null
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(editTourName ?: getString(R.string.new_route_default_name))
            if (!isNewTour) setSelection(text.length)
        }
        val inputContainer = FrameLayout(this).apply {
            setPadding(dp(DIALOG_FIELD_HORIZONTAL_PADDING_DP), 0, dp(DIALOG_FIELD_HORIZONTAL_PADDING_DP), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (editTourReference != null) R.string.save_tour_changes_title else R.string.save_route_title)
            .setView(inputContainer)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                if (editTourReference == null) R.string.save_route else R.string.save_changes,
            ) { _, _ ->
                saveRoute(
                    route = route.copy(name = input.text.toString().trim().ifEmpty {
                        getString(R.string.new_route_default_name)
                    }),
                    returnToTourLibraryAfterSave = returnToTourLibraryAfterSave,
                )
            }
            .create()
        dialog.setOnShowListener {
            if (isNewTour) {
                input.requestFocus()
                input.selectAll()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                input.post {
                    input.selectAll()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            } else {
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
        }
        dialog.show()
    }

    private fun saveRoute(
        route: GpxTrack,
        returnToTourLibraryAfterSave: Boolean = false,
    ) {
        val controlPoints = currentSemanticControlPoints()
        val activeRecordingSessionId = recordingSessionId
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (activeRecordingSessionId != null) {
                        check(trackStore.activeSession()?.id == activeRecordingSessionId) {
                            getString(R.string.recording_no_longer_active)
                        }
                        recordingRouteStore.save(activeRecordingSessionId, route, controlPoints)
                        detourStore.clear(activeRecordingSessionId)
                        null
                    } else editTourReference?.let { reference ->
                        trackStore.updateImportedTrack(reference, route, controlPoints)
                    } ?: trackStore.saveImportedTrack(
                            route,
                            plannedSource = TrackStore.PlannedTourSource.ROUTER,
                            routeControlPoints = controlPoints,
                        )
                }
            }
            result.onSuccess { stored ->
                if (activeRecordingSessionId != null) {
                    toast(getString(R.string.navigation_route_updated))
                    setResult(RESULT_OK)
                    finish()
                    return@onSuccess
                }
                checkNotNull(stored)
                val edited = editTourReference != null
                toast(getString(if (edited) R.string.tour_changes_saved else R.string.route_saved))
                if (returnToTourLibraryAfterSave) {
                    openTourLibrary()
                } else if (edited) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_EDIT_TOUR_REFERENCE, stored.reference))
                } else {
                    openSavedRoutePreview(stored.reference)
                }
                finish()
            }.onFailure { error ->
                toast(
                    getString(
                        R.string.tour_open_error,
                        error.localizedMessage ?: getString(R.string.not_available),
                    ),
                )
            }
        }
    }

    private fun currentSemanticControlPoints(): List<TrackStore.RouteControlPoint> =
        waypoints.map { point -> TrackStore.RouteControlPoint(point, waypointNames[point]) }

    private fun restoreSemanticControlPoints(
        track: GpxTrack,
        storedPoints: List<TrackStore.RouteControlPoint>,
    ): List<TrackStore.RouteControlPoint> {
        if (storedPoints.isEmpty()) {
            return RouteControlPointExtractor.extract(track).map { TrackStore.RouteControlPoint(it) }
        }
        if (!RouteVariantPolicy.isClosed(track) || !looksLikeExtractedLoopGeometry(storedPoints)) {
            return storedPoints
        }

        val namedPoints = storedPoints
            .filter { !it.label.isNullOrBlank() }
            .distinctBy { coordinateKey(it.point) }
        if (namedPoints.size >= 2) return namedPoints

        val start = storedPoints.first()
        val turningPoint = storedPoints
            .asSequence()
            .drop(1)
            .maxByOrNull { GeoMath.distanceMeters(start.point, it.point) }
            ?: return storedPoints
        return listOf(start, turningPoint)
    }

    private fun looksLikeExtractedLoopGeometry(points: List<TrackStore.RouteControlPoint>): Boolean =
        points.size >= 3 && GeoMath.distanceMeters(points.first().point, points.last().point) <= 2.0

    private fun coordinateKey(point: TrackPoint): String =
        String.format(Locale.US, "%.6f,%.6f", point.latitude, point.longitude)

    private fun openSavedRoutePreview(reference: String) {
        startActivities(
            arrayOf(
                Intent(this, TourLibraryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                Intent(this, TourDetailActivity::class.java)
                    .putExtra(TourDetailActivity.EXTRA_TOUR_REFERENCE, reference)
                    .putExtra(TourDetailActivity.EXTRA_OFFER_OFFLINE_MAP, true),
            ),
        )
    }

    private fun openTourLibrary() {
        startActivity(
            Intent(this, TourLibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun renderPlannerState() {
        val busy = isBusy()
        binding.activityTypeButton.text = getString(
            R.string.activity_type_button,
            getString(activityType.labelRes()),
        )
        binding.routeModeButton.text = getString(
            R.string.route_mode_button,
            getString(routeMode.labelRes),
        )
        val instructionRes = when {
            routingInProgress && roundTripPhase == RoundTripPhase.OUTBOUND ->
                R.string.calculating_return_routes
            routingInProgress -> R.string.calculating_route
            roundTripPhase == RoundTripPhase.OUTBOUND -> R.string.planner_choose_outbound
            roundTripPhase == RoundTripPhase.RETURN -> R.string.planner_choose_return
            roundTripPhase == RoundTripPhase.COMPLETE -> R.string.planner_round_trip_ready
            locationInProgress -> R.string.planner_locating
            searchInProgress -> R.string.searching_place
            pendingMapRole == PointRole.START -> R.string.planner_tap_start
            pendingMapRole == PointRole.DESTINATION -> R.string.planner_tap_destination
            pendingMapRole == PointRole.VIA -> R.string.planner_tap_via
            calculatedRoute != null -> R.string.planner_route_calculated
            destinationDraft != null -> R.string.planner_choose_start
            waypoints.isEmpty() -> R.string.planner_choose_start
            waypoints.size == 1 -> R.string.planner_choose_destination
            else -> R.string.planner_route_ready
        }
        val showRouteChoice = calculatedRoute != null &&
            !routingInProgress &&
            (routeAlternatives.size > 1 || roundTripPhase != RoundTripPhase.NONE)
        binding.instructionText.setText(instructionRes)
        binding.instructionRow.visibility = if (showRouteChoice || calculatedRoute != null) View.GONE else View.VISIBLE
        binding.routeChoiceTitle.setText(
            when (roundTripPhase) {
                RoundTripPhase.OUTBOUND -> R.string.planner_choose_outbound
                RoundTripPhase.RETURN -> R.string.planner_choose_return
                RoundTripPhase.COMPLETE -> R.string.planner_round_trip_ready
                RoundTripPhase.NONE -> R.string.planner_choose_variant
            },
        )
        binding.routeChoiceCard.visibility = if (showRouteChoice) View.VISIBLE else View.GONE
        binding.plannerCompactSummaryText.text = calculatedRoute?.let { route ->
            getString(
                R.string.planner_compact_route_summary,
                TrackAnalyzer.calculate(route).distanceMeters / 1_000.0,
                waypoints.size,
            )
        } ?: if (waypoints.isNotEmpty()) {
            getString(R.string.planner_compact_points_summary, waypoints.size)
        } else {
            getString(R.string.planner_compact_empty)
        }
        binding.startPointButton.text = waypoints.firstOrNull()?.let { point ->
            getString(R.string.planner_start_value, waypointNames[point] ?: coordinateLabel(point))
        } ?: getString(R.string.choose_start)
        val destinationPoint = destinationDraft ?: waypoints.lastOrNull()?.takeIf { waypoints.size >= 2 }
        binding.destinationPointButton.text = destinationPoint?.let { point ->
            getString(R.string.planner_destination_value, waypointNames[point] ?: coordinateLabel(point))
        } ?: getString(R.string.choose_destination)
        val showOrderedList = waypoints.size >= 2
        binding.initialPointControls.visibility = if (showOrderedList) View.GONE else View.VISIBLE
        binding.waypointList.visibility = if (showOrderedList) View.VISIBLE else View.GONE
        waypointAdapter.busy = busy
        renderWaypointEditorVisibility()
        binding.routingProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.calculateButton.visibility = if (
            calculatedRoute != null &&
            !routingInProgress &&
            roundTripPhase != RoundTripPhase.NONE
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.calculateButton.isEnabled = calculatedRoute != null && !busy &&
            roundTripPhase != RoundTripPhase.NONE
        binding.calculateButton.setText(
            when (roundTripPhase) {
                RoundTripPhase.RETURN -> R.string.select_return_route
                RoundTripPhase.COMPLETE -> R.string.save_route
                else -> R.string.use_outbound_route
            },
        )
        binding.calculateButton.setIconResource(
            if (roundTripPhase == RoundTripPhase.COMPLETE) R.drawable.ic_save else R.drawable.ic_route,
        )
        val canSave = canSaveCurrentRoute()
        binding.toolbar.menu.findItem(R.id.action_save_route)?.apply {
            isEnabled = canSave
            isVisible = canSave
            setTitle(
                when {
                    recordingSessionId != null -> R.string.use_navigation_route
                    editTourReference == null -> R.string.save_route
                    else -> R.string.save_changes
                },
            )
        }
        binding.startPointButton.isEnabled = !busy
        binding.destinationPointButton.isEnabled = !busy
        val hasEndpointToSwap = waypoints.isNotEmpty() || destinationDraft != null
        val hasSingleEndpoint = waypoints.size == 1 && destinationDraft == null ||
            waypoints.isEmpty() && destinationDraft != null
        binding.swapEndpointsButton.isEnabled = hasEndpointToSwap && !busy
        binding.swapEndpointsButton.visibility = if (hasEndpointToSwap) View.VISIBLE else View.GONE
        val swapDescription = getString(
            if (waypoints.size == 1 && destinationDraft == null) {
                R.string.move_start_to_destination
            } else if (waypoints.isEmpty() && destinationDraft != null) {
                R.string.move_destination_to_start
            } else if (calculatedRoute?.let(RouteVariantPolicy::isClosed) == true) {
                R.string.reverse_round_route_direction
            } else {
                R.string.reverse_route_direction
            },
        )
        binding.swapEndpointsButton.text = if (hasSingleEndpoint) swapDescription else null
        binding.swapEndpointsButton.updateLayoutParams<LinearLayout.LayoutParams> {
            if (hasSingleEndpoint) {
                width = 0
                weight = 1f
                marginStart = 0
            } else {
                width = dp(SWAP_BUTTON_COMPACT_WIDTH_DP)
                weight = 0f
                marginStart = dp(SWAP_BUTTON_MARGIN_START_DP)
            }
        }
        binding.swapEndpointsButton.contentDescription = swapDescription
        TooltipCompat.setTooltipText(binding.swapEndpointsButton, swapDescription)
        binding.addViaButton.visibility = if (waypoints.size >= 2) View.VISIBLE else View.GONE
        binding.addViaButton.isEnabled = !busy
        binding.alternativeSelector.visibility = if (routeAlternatives.size > 1) View.VISIBLE else View.GONE
        if (routeAlternatives.isNotEmpty()) {
            val stats = TrackAnalyzer.calculate(routeAlternatives[selectedAlternativeIndex])
            binding.alternativeLabel.text = getString(
                when (roundTripPhase) {
                    RoundTripPhase.OUTBOUND -> R.string.route_outbound_summary
                    RoundTripPhase.RETURN,
                    RoundTripPhase.COMPLETE,
                    -> R.string.route_return_summary
                    RoundTripPhase.NONE -> R.string.route_alternative_summary
                },
                selectedAlternativeIndex + 1,
                routeAlternatives.size,
                stats.distanceMeters / 1_000.0,
            )
        }
        val canUndo = editHistory.isNotEmpty() && !busy
        binding.toolbar.menu.findItem(R.id.action_undo_route_edit)?.apply {
            isEnabled = canUndo
            isVisible = canUndo
        }
        binding.activityTypeButton.isEnabled = !busy
        binding.routeModeButton.isEnabled = !busy
        binding.root.post(::updateCenterButtonPosition)
    }

    private fun waypointListLabel(index: Int): String {
        val point = waypoints[index]
        val label = waypointNames[point] ?: coordinateLabel(point)
        return when (index) {
            0 -> getString(R.string.planner_start_value, label)
            waypoints.lastIndex -> getString(R.string.planner_destination_value, label)
            else -> getString(R.string.planner_via_value, index, label)
        }
    }

    private fun requestCenterOnUser() {
        if (hasLocationPermission()) {
            centerOnUser()
        } else {
            pendingCenterRequest = true
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun centerOnUser() {
        if (!hasLocationPermission()) return
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                toast(getString(R.string.planner_location_unavailable))
                return
            }
        }
        val fallback = findRecentLastKnownLocation()
        fallback?.let(::showUserLocation)
        centerLocationSignal?.cancel()
        val signal = CancellationSignal()
        centerLocationSignal = signal
        LocationManagerCompat.getCurrentLocation(
            locationManager,
            provider,
            signal,
            ContextCompat.getMainExecutor(this),
        ) { location ->
            if (centerLocationSignal !== signal) return@getCurrentLocation
            centerLocationSignal = null
            if (location != null) showUserLocation(location)
            else if (fallback == null) toast(getString(R.string.planner_location_unavailable))
        }
    }

    private fun showUserLocation(location: android.location.Location) {
        val readyMap = map ?: return
        userLocationMarker?.let(readyMap::removeMarker)
        userLocationMarker = readyMap.addMarker(
            MarkerOptions()
                .position(LatLng(location.latitude, location.longitude))
                .icon(createUserLocationIcon()),
        )
        readyMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), USER_ZOOM),
            500,
        )
    }

    private fun createUserLocationIcon(): Icon {
        val size = dp(30).coerceAtLeast(30)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint)
        paint.color = Color.parseColor("#2878E8")
        canvas.drawCircle(size / 2f, size / 2f, size * 0.36f, paint)
        return IconFactory.getInstance(this).fromBitmap(bitmap)
    }

    private fun updateCenterButtonPosition() {
        if (binding.pointSearchOverlay.visibility == View.VISIBLE) {
            binding.centerButton.visibility = View.GONE
            return
        }
        val visibleDrawerHeight = (binding.plannerCard.bottom - binding.plannerCard.top).coerceAtLeast(0)
        val availableMapHeight = binding.plannerCard.top - binding.toolbar.bottom
        binding.centerButton.translationY = -visibleDrawerHeight.toFloat()
        binding.centerButton.visibility = if (
            availableMapHeight >= binding.centerButton.height +
            dp(CENTER_BUTTON_CLEARANCE_DP)
        ) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
    }

    private fun revealPlannerAfterStructureChange() {
        drawerSpring?.cancel()
        binding.plannerCard.translationY = 0f
        plannerDrawerController.expand()
        schedulePlannerExtentUpdate()
    }

    private fun requestLocationForRole(role: PointRole, viaIndex: Int? = null) {
        if (hasLocationPermission()) {
            locateForRole(role, viaIndex)
        } else {
            pendingLocationRole = role
            pendingLocationViaIndex = viaIndex
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun locateForRole(role: PointRole, viaIndex: Int? = null) {
        if (!hasLocationPermission()) return
        locationLookupJob?.cancel()
        locationSignal?.cancel()
        centerLocationSignal?.cancel()
        val signal = CancellationSignal()
        locationSignal = signal
        locationInProgress = true
        renderPlannerState()
        toast(getString(R.string.planner_locating))
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }
        locationLookupJob = lifecycleScope.launch {
            delay(LOCATION_LOOKUP_TIMEOUT_MS)
            if (locationSignal !== signal) return@launch
            signal.cancel()
            val fallback = findRecentLastKnownLocation()
            if (fallback != null) {
                completeLocationLookup(signal, role, viaIndex, fallback, usedLastKnown = true)
            } else {
                failLocationLookup(signal, role, viaIndex)
            }
        }
        runCatching {
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                signal,
                ContextCompat.getMainExecutor(this),
            ) { location ->
                if (locationSignal !== signal) return@getCurrentLocation
                if (location == null) {
                    val fallback = findRecentLastKnownLocation()
                    if (fallback != null) {
                        completeLocationLookup(signal, role, viaIndex, fallback, usedLastKnown = true)
                    } else {
                        failLocationLookup(signal, role, viaIndex)
                    }
                    return@getCurrentLocation
                }
                completeLocationLookup(signal, role, viaIndex, location, usedLastKnown = false)
            }
        }.onFailure {
            val fallback = findRecentLastKnownLocation()
            if (fallback != null) {
                completeLocationLookup(signal, role, viaIndex, fallback, usedLastKnown = true)
            } else {
                failLocationLookup(signal, role, viaIndex)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findRecentLastKnownLocation() = locationManager.allProviders
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .filter { location ->
            val ageMillis = if (location.elapsedRealtimeNanos > 0L) {
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            } else {
                System.currentTimeMillis() - location.time
            }
            ageMillis in 0..LAST_LOCATION_MAX_AGE_MS
        }
        .maxByOrNull { it.elapsedRealtimeNanos.takeIf { nanos -> nanos > 0L } ?: it.time * 1_000_000L }

    private fun completeLocationLookup(
        signal: CancellationSignal,
        role: PointRole,
        viaIndex: Int?,
        location: android.location.Location,
        usedLastKnown: Boolean,
    ) {
        if (locationSignal !== signal) return
        locationLookupJob?.cancel()
        locationLookupJob = null
        locationSignal = null
        locationInProgress = false
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            elevationMeters = location.altitude.takeIf { location.hasAltitude() },
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
        )
        val saveAsHome = pendingHomeSave
        pendingHomeSave = false
        if (saveAsHome) {
            getSharedPreferences(HOME_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(HOME_LATITUDE, point.latitude.toString())
                .putString(HOME_LONGITUDE, point.longitude.toString())
                .apply()
            toast(getString(R.string.home_location_saved))
        } else if (usedLastKnown) {
            toast(getString(R.string.planner_use_last_location, location.accuracy.toInt()))
        }
        applyPoint(
            role,
            point,
            getString(if (saveAsHome) R.string.home_location else R.string.current_position_short),
            viaIndex,
        )
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), USER_ZOOM),
            500,
        )
    }

    private fun failLocationLookup(signal: CancellationSignal, role: PointRole, viaIndex: Int?) {
        if (locationSignal !== signal) return
        locationLookupJob?.cancel()
        locationLookupJob = null
        locationSignal = null
        locationInProgress = false
        pendingHomeSave = false
        renderPlannerState()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.planner_location_unavailable)
            .setMessage(R.string.planner_location_unavailable_message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.select_on_map_short) { _, _ ->
                pendingMapRole = role
                pendingMapViaIndex = viaIndex
                renderPlannerState()
            }
            .setPositiveButton(R.string.try_again) { _, _ -> locateForRole(role, viaIndex) }
            .show()
    }

    private fun framePoints(points: List<TrackPoint>) {
        if (points.isEmpty()) {
            pendingFramePoints = null
            return
        }
        val readyMap = map
        if (
            readyMap == null ||
            mapStyle == null ||
            plannerSheetBehavior.state == BottomSheetBehavior.STATE_DRAGGING ||
            plannerSheetBehavior.state == BottomSheetBehavior.STATE_SETTLING
        ) {
            pendingFramePoints = points.toList()
            return
        }
        pendingFramePoints = null
        if (points.size == 1) {
            readyMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(points.first().latitude, points.first().longitude),
                    USER_ZOOM,
                ),
                400,
            )
            return
        }
        val bounds = LatLngBounds.Builder().also { builder ->
            points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        }.build()
        binding.mapView.post {
            val density = resources.displayMetrics.density
            val side = (28 * density).toInt()
            val top = binding.toolbar.height + side
            val visibleDrawerHeight = (binding.plannerCard.bottom - binding.plannerCard.top)
                .coerceAtLeast(plannerSheetBehavior.peekHeight)
            val bottom = visibleDrawerHeight + side
            readyMap.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, side, top, side, bottom),
                ROUTE_FRAME_ANIMATION_MS.toInt(),
            )
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isBusy(): Boolean = routingInProgress || searchInProgress || locationInProgress || editLoadInProgress

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { binding.mapView.onPause(); super.onPause() }
    override fun onStop() { binding.mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() {
        pointSearchJob?.cancel()
        autoRoutingJob?.cancel()
        locationLookupJob?.cancel()
        locationSignal?.cancel()
        binding.mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    private inner class WaypointAdapter : RecyclerView.Adapter<WaypointViewHolder>() {
        var busy: Boolean = false

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): WaypointViewHolder =
            WaypointViewHolder(
                ItemRouteWaypointBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun getItemCount(): Int = displayedWaypointRowCount()

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: WaypointViewHolder, position: Int) {
            val isSummary = isCollapsedWaypointSummary(position)
            val waypointIndex = waypointIndexForAdapterPosition(position)
            holder.binding.waypointButton.apply {
                text = if (isSummary) {
                    resources.getQuantityString(
                        R.plurals.intermediate_waypoint_count,
                        waypoints.size - 2,
                        waypoints.size - 2,
                    )
                } else {
                    waypointIndex?.let(::waypointListLabel).orEmpty()
                }
                isEnabled = !busy
                setOnClickListener {
                    val adapterPosition = holder.bindingAdapterPosition
                    when {
                        adapterPosition == RecyclerView.NO_POSITION -> Unit
                        isCollapsedWaypointSummary(adapterPosition) -> toggleWaypointEditor()
                        else -> waypointIndexForAdapterPosition(adapterPosition)?.let(::openWaypoint)
                    }
                }
            }
            holder.binding.waypointExpandButton.apply {
                val isFirstExpandedVia = waypointEditorExpanded && waypointIndex == 1 &&
                    waypoints.size >= MIN_COLLAPSIBLE_WAYPOINT_COUNT
                visibility = if (isSummary || isFirstExpandedVia) View.VISIBLE else View.GONE
                setImageResource(if (isSummary) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
                contentDescription = getString(
                    if (isSummary) R.string.expand_waypoints else R.string.collapse_waypoints,
                )
                isEnabled = !busy
                alpha = if (isEnabled) 1f else 0.35f
                setOnClickListener { if (!isBusy()) toggleWaypointEditor() }
            }
            holder.binding.dragHandle.apply {
                visibility = if (isSummary) View.GONE else View.VISIBLE
                isEnabled = !busy && waypointEditorExpanded && waypointIndex != null && waypoints.size >= 2
                alpha = if (isEnabled) 1f else 0.35f
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && isEnabled) {
                        waypointTouchHelper.startDrag(holder)
                    }
                    false
                }
            }
            holder.binding.deleteWaypointButton.apply {
                val isVia = waypointIndex != null && waypointIndex in 1 until waypoints.lastIndex
                visibility = if (isVia) View.VISIBLE else View.GONE
                isEnabled = isVia && !busy
                alpha = if (isEnabled) 1f else 0.35f
                setOnClickListener {
                    val index = waypointIndexForAdapterPosition(holder.bindingAdapterPosition)
                    if (index != null && index in 1 until waypoints.lastIndex && !isBusy()) {
                        mutateWaypoints(preserveExpandedEditor = true) { removeAt(index) }
                    }
                }
            }
        }
    }

    private class WaypointViewHolder(
        val binding: ItemRouteWaypointBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    private data class EditableTour(
        val stored: TrackStore.StoredTour,
        val track: GpxTrack,
        val controlPoints: List<TrackStore.RouteControlPoint>,
    )

    private data class RuntimeEditableRoute(
        val activityType: ActivityType,
        val route: GpxTrack?,
        val controlPoints: List<TrackStore.RouteControlPoint>,
    )

    private data class EditBaseline(
        val waypoints: List<TrackPoint>,
        val activityType: ActivityType,
        val routeMode: RouteMode,
        val routeSignature: String?,
    )

    private data class PlannerUndoState(
        val waypoints: List<TrackPoint>,
        val waypointNames: Map<TrackPoint, String>,
        val destinationDraft: TrackPoint?,
        val calculatedRoute: GpxTrack?,
        val routeAlternatives: List<GpxTrack>,
        val selectedAlternativeIndex: Int,
        val roundTripPhase: RoundTripPhase,
        val activityType: ActivityType,
        val routeMode: RouteMode,
    )

    companion object {
        const val EXTRA_OPEN_SEARCH = "de.wandern.app.extra.OPEN_ROUTE_SEARCH"
        const val EXTRA_EDIT_TOUR_REFERENCE = "de.wandern.app.extra.EDIT_TOUR_REFERENCE"
        const val EXTRA_RECORDING_SESSION_ID = "de.wandern.app.extra.RECORDING_SESSION_ID"
        const val EXTRA_RECORDING_LATITUDE = "de.wandern.app.extra.RECORDING_LATITUDE"
        const val EXTRA_RECORDING_LONGITUDE = "de.wandern.app.extra.RECORDING_LONGITUDE"
        const val EXTRA_SEARCH_REFERENCE_LATITUDE = "de.wandern.app.extra.SEARCH_REFERENCE_LATITUDE"
        const val EXTRA_SEARCH_REFERENCE_LONGITUDE = "de.wandern.app.extra.SEARCH_REFERENCE_LONGITUDE"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val ROUTE_SOURCE = "planned-route-source"
        private const val ROUTE_LAYER = "planned-route-layer"
        private const val ROUTE_DIRECTION_LAYER = "planned-route-direction-layer"
        private const val ROUTE_DIRECTION_ICON = "planned-route-direction-icon"
        private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        private const val DEFAULT_ZOOM = 5.0
        private const val USER_ZOOM = 15.0
        private const val LOCATION_LOOKUP_TIMEOUT_MS = 10_000L
        private const val LAST_LOCATION_MAX_AGE_MS = 15 * 60_000L
        private const val AUTO_ROUTE_DEBOUNCE_MS = 350L
        private const val ROUTE_FRAME_ANIMATION_MS = 600L
        private const val ROUTE_FRAME_SETTLE_BUFFER_MS = 80L
        private const val HOME_PREFERENCES = "planner_home"
        private const val HOME_LATITUDE = "latitude"
        private const val HOME_LONGITUDE = "longitude"
        private const val NO_GO_RADIUS_METERS = 45
        private const val MAX_NO_GO_POINTS = 60
        private const val WAYPOINT_ROW_HEIGHT_DP = 52
        private const val MIN_COLLAPSIBLE_WAYPOINT_COUNT = 3
        private const val SIMPLE_DRAFT_MAX_POINT_COUNT = 2
        private const val COLLAPSED_WAYPOINT_ROW_COUNT = 3
        private const val COLLAPSED_WAYPOINT_SUMMARY_POSITION = 1
        private const val COLLAPSED_WAYPOINT_DESTINATION_POSITION = 2
        private const val DIALOG_FIELD_HORIZONTAL_PADDING_DP = 24
        private const val PLANNER_DRAWER_PEEK_HEIGHT_DP = 56
        private const val CENTER_BUTTON_CLEARANCE_DP = 32
        private const val DRAWER_OVERPULL_MAX_DP = 20
        private const val DRAWER_OVERPULL_RESISTANCE_DP = 72
        private const val DRAWER_SPRING_DAMPING = 0.58f
        private const val DRAWER_SPRING_STIFFNESS = 720f
        private const val SWAP_BUTTON_COMPACT_WIDTH_DP = 48
        private const val SWAP_BUTTON_MARGIN_START_DP = 4
        private val ALTERNATIVE_INDICES = 0..2
        private val DEFAULT_CENTER = LatLng(51.0, 10.0)
    }

    private enum class PointRole(val titleRes: Int) {
        START(R.string.choose_start),
        DESTINATION(R.string.choose_destination),
        VIA(R.string.add_via_point),
    }

    private enum class RoundTripPhase {
        NONE,
        OUTBOUND,
        RETURN,
        COMPLETE,
    }

    private enum class RouteMode(val labelRes: Int) {
        ONE_WAY(R.string.route_mode_one_way),
        OUT_AND_BACK(R.string.route_mode_out_and_back),
        ROUND_TRIP(R.string.route_mode_round_trip),
    }
}
