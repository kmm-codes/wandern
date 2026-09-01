package de.wandern.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import de.wandern.app.R
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.FitnessPreferences
import de.wandern.app.data.GpxCodec
import de.wandern.app.data.TrackStore
import de.wandern.app.localization.AppLanguage
import de.wandern.app.databinding.ActivityTourMapBinding
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.ProfileSample
import de.wandern.app.model.RoutePointInterpolator
import de.wandern.app.model.RouteSurface
import de.wandern.app.model.RouteWayType
import de.wandern.app.model.TourForecast
import de.wandern.app.model.TourForecaster
import de.wandern.app.model.TourInsights
import de.wandern.app.model.TourInsightsAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
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
import kotlin.math.roundToInt

class TourMapActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTourMapBinding
    private val displayLocale get() = AppLanguage.forContext(this).locale
    private lateinit var trackStore: TrackStore
    private lateinit var fitnessPreferences: FitnessPreferences
    private lateinit var dataSheetBehavior: BottomSheetBehavior<MaterialCardView>
    private lateinit var dataDrawerController: BottomDrawerController<MaterialCardView>
    private var loadedTour: LoadedTour? = null
    private var mapStyle: Style? = null
    private var panelSwipeCandidate = false
    private var panelSwipeStartX = 0f
    private var panelSwipeStartY = 0f
    private var panelSwipeStartTimeMillis = 0L
    private var systemBottomInsetPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityTourMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupDataBottomSheet()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            systemBottomInsetPx = systemBars.bottom
            dataDrawerController.applyWindowInsets(insets)
            binding.dataPanel.post(::updatePanelSystemOcclusion)
            insets
        }
        binding.mapView.onCreate(savedInstanceState)
        binding.toolbar.setNavigationContentDescription(R.string.cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.profileChart.onSelectionChanged = ::showProfilePositionOnMap
        trackStore = TrackStore(this)
        fitnessPreferences = FitnessPreferences(this)

        val reference = intent.getStringExtra(EXTRA_TOUR_REFERENCE)
        if (reference == null) {
            finish()
            return
        }
        loadTour(reference)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (detectPanelSwipe(event)) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun detectPanelSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panelSwipeCandidate = binding.dataPanel.visibility == View.VISIBLE &&
                    isInsideDataPanel(event.rawX.toInt(), event.rawY.toInt())
                panelSwipeStartX = event.x
                panelSwipeStartY = event.y
                panelSwipeStartTimeMillis = event.eventTime
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                panelSwipeCandidate = false
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (!panelSwipeCandidate) return false
                panelSwipeCandidate = false
                val direction = HorizontalSwipeClassifier.classify(
                    deltaX = event.x - panelSwipeStartX,
                    deltaY = event.y - panelSwipeStartY,
                    durationMillis = event.eventTime - panelSwipeStartTimeMillis,
                    minimumDistance = PANEL_SWIPE_MIN_DISTANCE_DP * resources.displayMetrics.density,
                    horizontalRatio = PANEL_SWIPE_HORIZONTAL_RATIO,
                    maximumDurationMillis = PANEL_SWIPE_MAX_DURATION_MILLIS,
                ) ?: return false
                return switchPanel(direction)
            }
            else -> return false
        }
    }

    private fun isInsideDataPanel(screenX: Int, screenY: Int): Boolean {
        val bounds = Rect()
        return binding.dataPanel.getGlobalVisibleRect(bounds) && bounds.contains(screenX, screenY)
    }

    private fun switchPanel(direction: HorizontalSwipeDirection): Boolean {
        val visiblePanels = listOf(
            binding.primaryPanelButton,
            binding.secondaryPanelButton,
            binding.wayTypesPanelButton,
            binding.surfacesPanelButton,
        ).filter { it.visibility == View.VISIBLE }
        val currentIndex = visiblePanels.indexOfFirst { it.id == binding.panelToggle.checkedButtonId }
        if (currentIndex < 0) return false
        val targetIndex = when (direction) {
            HorizontalSwipeDirection.LEFT -> currentIndex + 1
            HorizontalSwipeDirection.RIGHT -> currentIndex - 1
        }
        val target = visiblePanels.getOrNull(targetIndex)?.id ?: return false
        binding.panelToggle.check(target)
        return true
    }

    private fun loadTour(reference: String) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stored = trackStore.listStoredTours().firstOrNull { it.reference == reference }
                        ?: error(getString(R.string.tour_not_found))
                    val originalTrack = trackStore.loadStoredTrack(reference)
                    val track = runCatching { ElevationEnricher().enrichIfMissing(originalTrack) }
                        .getOrDefault(originalTrack)
                    if (track.elevationSource != originalTrack.elevationSource) {
                        stored.file.writeText(GpxCodec.encode(track), Charsets.UTF_8)
                    }
                    LoadedTour(stored, track, TourInsightsAnalyzer.analyze(track))
                }
            }
            result.onSuccess(::renderTour).onFailure {
                Toast.makeText(
                    this@TourMapActivity,
                    getString(R.string.tour_statistics_error, it.localizedMessage ?: getString(R.string.unknown_error)),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }

    private fun renderTour(loaded: LoadedTour) {
        loadedTour = loaded
        binding.toolbar.title = loaded.track.name
        val planned = loaded.stored.origin == TrackStore.StoredTourOrigin.IMPORTED
        binding.primaryPanelButton.setText(if (planned) R.string.tour_map_elevation_tab else R.string.tour_map_speed_tab)
        binding.secondaryPanelButton.setText(if (planned) R.string.tour_map_forecast else R.string.tour_map_elevation_tab)
        val hasRouteAttributes = loaded.track.routeAttributes.isNotEmpty()
        binding.wayTypesPanelButton.visibility = if (hasRouteAttributes) View.VISIBLE else View.GONE
        binding.surfacesPanelButton.visibility = if (hasRouteAttributes) View.VISIBLE else View.GONE
        binding.panelToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) renderPanel(checkedId)
        }
        binding.panelToggle.check(binding.primaryPanelButton.id)
        renderPanel(binding.primaryPanelButton.id)
        renderMap(loaded.track)
    }

    private fun renderPanel(checkedId: Int) {
        val loaded = loadedTour ?: return
        showProfilePositionOnMap(null)
        val planned = loaded.stored.origin == TrackStore.StoredTourOrigin.IMPORTED
        when (checkedId) {
            binding.primaryPanelButton.id -> if (planned) {
                renderElevationPanel(loaded.insights)
            } else {
                renderSpeedPanel(loaded.insights)
            }
            binding.secondaryPanelButton.id -> if (planned) {
                renderForecastPanel(loaded.insights)
            } else {
                renderElevationPanel(loaded.insights)
            }
            binding.wayTypesPanelButton.id -> renderRouteAttributes(RouteAttributesView.Dimension.WAY_TYPE)
            binding.surfacesPanelButton.id -> renderRouteAttributes(RouteAttributesView.Dimension.SURFACE)
        }
    }

    private fun renderSpeedPanel(insights: TourInsights) {
        val stats = insights.stats
        binding.panelSummary.text = getString(
            R.string.tour_map_recorded_summary,
            formatDistance(stats.distanceMeters),
            formatDuration(stats.movingDurationMillis),
            stats.averageSpeedMetersPerSecond.takeIf { insights.hasTimeData && it > 0.0 }
                ?.let { String.format(displayLocale, "%.1f km/h", it * 3.6) }
                ?: getString(R.string.not_available),
        )
        binding.profileChart.visibility = View.VISIBLE
        binding.forecastDetailsText.visibility = View.GONE
        binding.routeAttributesView.visibility = View.GONE
        binding.profileChart.setSeries(
            samples = insights.speedProfile,
            unit = "km/h",
            color = Color.parseColor("#F26B38"),
            emptyMessage = getString(
                if (insights.hasTimeData) R.string.no_speed_data else R.string.no_time_data_for_speed,
            ),
            includeZero = true,
            selectionFormatter = ::formatSpeedSelection,
        )
    }

    private fun renderElevationPanel(insights: TourInsights) {
        val stats = insights.stats
        binding.panelSummary.text = getString(
            R.string.tour_map_elevation_summary,
            formatDistance(stats.distanceMeters),
            formatMeters(stats.ascentMeters),
            formatMeters(stats.descentMeters),
        )
        binding.profileChart.visibility = View.VISIBLE
        binding.forecastDetailsText.visibility = View.GONE
        binding.routeAttributesView.visibility = View.GONE
        binding.profileChart.setSeries(
            samples = insights.elevationProfile,
            unit = "m",
            color = Color.parseColor("#1E4D3C"),
            emptyMessage = getString(R.string.no_elevation_data),
            minimumValueRange = 100.0,
            colorBySlope = true,
            selectionFormatter = ::formatElevationSelection,
        )
    }

    private fun renderForecastPanel(insights: TourInsights) {
        val forecast = TourForecaster.forecast(
            insights.stats,
            insights.elevationProfile,
            fitnessPreferences.level,
        )
        binding.profileChart.visibility = View.GONE
        binding.forecastDetailsText.visibility = View.VISIBLE
        binding.routeAttributesView.visibility = View.GONE
        binding.panelSummary.text = getString(
            R.string.tour_map_elevation_summary,
            formatDistance(insights.stats.distanceMeters),
            formatMeters(insights.stats.ascentMeters),
            formatMeters(insights.stats.descentMeters),
        )
        binding.forecastDetailsText.text = forecast?.let(::formatForecast)
            ?: getString(R.string.not_available)
    }

    private fun renderRouteAttributes(dimension: RouteAttributesView.Dimension) {
        val segments = loadedTour?.track?.routeAttributes.orEmpty()
        val totalDistance = segments.sumOf { it.distanceMeters }
        val unknownDistance = segments.filter { segment ->
            when (dimension) {
                RouteAttributesView.Dimension.WAY_TYPE -> segment.wayType == RouteWayType.UNKNOWN
                RouteAttributesView.Dimension.SURFACE -> segment.surface == RouteSurface.UNKNOWN
            }
        }.sumOf { it.distanceMeters }
        val coverage = if (totalDistance > 0.0) {
            ((totalDistance - unknownDistance) / totalDistance * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        binding.panelSummary.text = getString(
            R.string.route_attribute_coverage,
            formatDistance(totalDistance),
            coverage,
        )
        binding.profileChart.visibility = View.GONE
        binding.forecastDetailsText.visibility = View.GONE
        binding.routeAttributesView.visibility = View.VISIBLE
        binding.routeAttributesView.setSegments(segments, dimension)
    }

    private fun formatForecast(forecast: TourForecast): String = getString(
        R.string.tour_map_forecast_summary,
        formatDuration(forecast.totalDurationMillis),
        formatDuration(forecast.movingDurationMillis),
        formatDuration(forecast.breakDurationMillis),
        String.format(displayLocale, "%.1f km/h", forecast.averageSpeedKilometersPerHour),
        formatPace(forecast.paceSecondsPerKilometer),
    )

    private fun setupDataBottomSheet() {
        dataDrawerController = BottomDrawerController(
            sheet = binding.dataPanel,
            content = binding.dataPanelContent,
            basePeekHeightPx = dp(DATA_DRAWER_PEEK_HEIGHT_DP),
            onStableStateChanged = ::updateDrawerToggle,
        )
        dataSheetBehavior = dataDrawerController.behavior
        binding.hideDataButton.setOnClickListener { dataDrawerController.toggle() }
        binding.dataPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePanelSystemOcclusion()
        }
        updateDrawerToggle(dataSheetBehavior.state)
    }

    private fun updatePanelSystemOcclusion() {
        if (systemBottomInsetPx <= 0 || !binding.profileChart.isLaidOut) {
            binding.profileChart.setBottomSystemOcclusion(0)
            binding.routeAttributesView.setBottomSystemOcclusion(0)
            return
        }
        val rootLocation = IntArray(2)
        val chartLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        binding.profileChart.getLocationOnScreen(chartLocation)
        val navigationTop = rootLocation[1] + binding.root.height - systemBottomInsetPx
        val chartBottom = chartLocation[1] + binding.profileChart.height
        val occlusion = (chartBottom - navigationTop).coerceIn(0, systemBottomInsetPx)
        binding.profileChart.setBottomSystemOcclusion(occlusion)
        binding.routeAttributesView.setBottomSystemOcclusion(occlusion)
    }

    private fun updateDrawerToggle(state: Int) {
        val collapsed = state == BottomSheetBehavior.STATE_COLLAPSED
        binding.hideDataButton.setIconResource(
            if (collapsed) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
        )
        binding.hideDataButton.setContentDescription(
            getString(if (collapsed) R.string.show_tour_data else R.string.hide_tour_data),
        )
        binding.dataPanel.post(::updatePanelSystemOcclusion)
    }

    private fun renderMap(track: GpxTrack) {
        binding.mapView.getMapAsync { map ->
            map.addOnMapClickListener { coordinate -> showMapPoi(map, coordinate) }
            map.uiSettings.apply {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isCompassEnabled = true
                isAttributionEnabled = true
                isLogoEnabled = false
            }
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                MapStyleLocalizer.localize(style, AppLanguage.forContext(this))
                mapStyle = style
                val features = track.segments.filter { it.size >= 2 }.map { segment ->
                    Feature.fromGeometry(
                        LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }),
                    )
                }
                style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(features)))
                style.addLayer(
                    LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(Color.parseColor("#1677FF")),
                        lineWidth(5f),
                        lineOpacity(0.92f),
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
                style.addSource(GeoJsonSource(PROFILE_POSITION_SOURCE, EMPTY_FEATURE_COLLECTION))
                style.addLayer(
                    CircleLayer(PROFILE_POSITION_LAYER, PROFILE_POSITION_SOURCE).withProperties(
                        circleColor(Color.WHITE),
                        circleRadius(7f),
                        circleStrokeColor(Color.parseColor("#0D2B22")),
                        circleStrokeWidth(4f),
                    ),
                )
                addEndpointMarkers(map, track)
                fitRoute(map, track)
            }
        }
    }

    private fun showMapPoi(map: MapLibreMap, coordinate: LatLng): Boolean =
        MapPoiDialog.show(
            activity = this,
            map = map,
            coordinate = coordinate,
        )

    private fun showProfilePositionOnMap(distanceMeters: Double?) {
        val source = mapStyle?.getSourceAs<GeoJsonSource>(PROFILE_POSITION_SOURCE) ?: return
        val track = loadedTour?.track
        val position = if (distanceMeters != null && track != null) {
            RoutePointInterpolator.pointAtDistance(track, distanceMeters)
        } else {
            null
        }
        if (position == null) {
            source.setGeoJson(EMPTY_FEATURE_COLLECTION)
        } else {
            source.setGeoJson(
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(position.longitude, position.latitude)),
                ),
            )
        }
    }

    private fun fitRoute(map: MapLibreMap, track: GpxTrack) {
        val points = track.points
        if (points.size == 1) {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(points.first().latitude, points.first().longitude),
                    15.0,
                ),
            )
        } else if (points.size > 1) {
            val bounds = LatLngBounds.Builder().also { builder ->
                points.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
            }.build()
            binding.mapView.post {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        (52 * resources.displayMetrics.density).toInt(),
                    ),
                )
            }
        }
    }

    private fun addEndpointMarkers(map: MapLibreMap, track: GpxTrack) {
        val start = track.points.firstOrNull() ?: return
        val end = track.points.lastOrNull() ?: return
        if (GeoMath.distanceMeters(start, end) >= CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS) {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(end.latitude, end.longitude))
                    .icon(createEndpointIcon("Z", Color.parseColor("#C44431"))),
            )
        }
        map.addMarker(
            MarkerOptions()
                .position(LatLng(start.latitude, start.longitude))
                .icon(createEndpointIcon("S", Color.parseColor("#1E4D3C"))),
        )
    }

    private fun createEndpointIcon(label: String, color: Int): Icon {
        val density = resources.displayMetrics.density
        val size = (34 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val strokeWidth = 2.5f * density
        val radius = center - strokeWidth
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, radius, paint)
        paint.color = color
        canvas.drawCircle(center, center, radius - strokeWidth, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 15 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(label, center, center - (paint.ascent() + paint.descent()) / 2f, paint)
        return IconFactory.getInstance(this).fromBitmap(bitmap)
    }

    private fun formatElevationSelection(sample: ProfileSample): String {
        val slope = sample.secondaryValue?.let { String.format(displayLocale, "%+.1f %%", it) } ?: "—"
        return String.format(
            displayLocale,
            "%.2f km · %.0f m · %s",
            sample.distanceMeters / 1_000.0,
            sample.value,
            slope,
        )
    }

    private fun formatSpeedSelection(sample: ProfileSample): String = String.format(
        displayLocale,
        "%.2f km · %.1f km/h",
        sample.distanceMeters / 1_000.0,
        sample.value,
    )

    private fun formatDistance(distanceMeters: Double) =
        String.format(displayLocale, "%.2f km", distanceMeters / 1_000.0)

    private fun formatMeters(meters: Double) = String.format(displayLocale, "%.0f m", meters)

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) String.format(displayLocale, "%d:%02d h", hours, minutes)
        else String.format(displayLocale, "%d min", minutes)
    }

    private fun formatPace(secondsPerKilometer: Double): String {
        val seconds = secondsPerKilometer.toInt().coerceAtMost(99 * 60 + 59)
        return String.format(displayLocale, "%d:%02d min/km", seconds / 60, seconds % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    private data class LoadedTour(
        val stored: TrackStore.StoredTour,
        val track: GpxTrack,
        val insights: TourInsights,
    )

    companion object {
        const val EXTRA_TOUR_REFERENCE = "de.wandern.app.TOUR_MAP_REFERENCE"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val ROUTE_SOURCE = "tour-map-route-source"
        private const val ROUTE_LAYER = "tour-map-route-layer"
        private const val ROUTE_DIRECTION_LAYER = "tour-map-route-direction-layer"
        private const val ROUTE_DIRECTION_ICON = "tour-map-route-direction-icon"
        private const val PROFILE_POSITION_SOURCE = "tour-map-profile-position-source"
        private const val PROFILE_POSITION_LAYER = "tour-map-profile-position-layer"
        private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        private const val CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS = 50.0
        private const val DATA_DRAWER_PEEK_HEIGHT_DP = 48
        private const val PANEL_SWIPE_MIN_DISTANCE_DP = 64f
        private const val PANEL_SWIPE_HORIZONTAL_RATIO = 1.35f
        private const val PANEL_SWIPE_MAX_DURATION_MILLIS = 450L
    }
}
