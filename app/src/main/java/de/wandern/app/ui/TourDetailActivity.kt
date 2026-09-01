package de.wandern.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.localization.AppLanguage
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.FitnessPreferences
import de.wandern.app.data.GpxCodec
import de.wandern.app.data.OfflineMapAvailability
import de.wandern.app.data.OfflineMapDownloadState
import de.wandern.app.data.OfflineMapDownloader
import de.wandern.app.data.OfflineMapStatus
import de.wandern.app.databinding.ActivityTourDetailBinding
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.HikingFitnessLevel
import de.wandern.app.model.OfflineMapPlanner
import de.wandern.app.model.TourForecaster
import de.wandern.app.model.TourInsights
import de.wandern.app.model.TourInsightsAnalyzer
import de.wandern.app.model.TrackPoint
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
import java.text.DateFormat
import java.io.File
import java.util.Date

class TourDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTourDetailBinding
    private val displayLocale get() = AppLanguage.forContext(this).locale
    private lateinit var trackStore: TrackStore
    private lateinit var fitnessPreferences: FitnessPreferences
    private lateinit var offlineMapDownloader: OfflineMapDownloader
    private var loadedTour: LoadedTour? = null
    private var offlineMapStatus = OfflineMapStatus(OfflineMapAvailability.CHECKING)
    private var exportSource: File? = null
    private var offerOfflineMapAfterLoad = false
    private var detailsSwipeCandidate = false
    private var detailsSwipeStartX = 0f
    private var detailsSwipeStartY = 0f
    private var detailsSwipeStartTimeMillis = 0L

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        val source = exportSource
        exportSource = null
        if (uri != null && source != null) exportTrack(source, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityTourDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binding.toolbar.setNavigationContentDescription(R.string.cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.previewMapView.onCreate(savedInstanceState)
        binding.elevationChart.onSelectionChanged = ::selectProfileDistance
        binding.speedChart.onSelectionChanged = ::selectProfileDistance
        binding.detailsPanelToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) renderDetailsPanel(checkedId)
        }
        trackStore = TrackStore(this)
        fitnessPreferences = FitnessPreferences(this)
        offlineMapDownloader = OfflineMapDownloader(this, MAP_STYLE_URL)

        val reference = intent.getStringExtra(EXTRA_TOUR_REFERENCE)
        if (reference == null) {
            finish()
            return
        }
        offerOfflineMapAfterLoad = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OFFER_OFFLINE_MAP, false)
        intent.removeExtra(EXTRA_OFFER_OFFLINE_MAP)
        binding.openMapButton.setOnClickListener { loadedTour?.let(::startTour) }
        binding.fitnessProfileButton.setOnClickListener { showFitnessProfileDialog() }
        binding.previewMapCard.setOnClickListener { openInteractiveMap(reference) }
        binding.previewMapExpandButton.setOnClickListener { openInteractiveMap(reference) }
        loadTour(reference)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (detectDetailsCarouselSwipe(event)) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun detectDetailsCarouselSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                detailsSwipeCandidate = isInsideDetailsCarousel(event.rawX.toInt(), event.rawY.toInt())
                detailsSwipeStartX = event.x
                detailsSwipeStartY = event.y
                detailsSwipeStartTimeMillis = event.eventTime
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                detailsSwipeCandidate = false
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (!detailsSwipeCandidate) return false
                detailsSwipeCandidate = false
                val direction = HorizontalSwipeClassifier.classify(
                    deltaX = event.x - detailsSwipeStartX,
                    deltaY = event.y - detailsSwipeStartY,
                    durationMillis = event.eventTime - detailsSwipeStartTimeMillis,
                    minimumDistance = DETAILS_SWIPE_MIN_DISTANCE_DP * resources.displayMetrics.density,
                    horizontalRatio = DETAILS_SWIPE_HORIZONTAL_RATIO,
                    maximumDurationMillis = DETAILS_SWIPE_MAX_DURATION_MILLIS,
                ) ?: return false
                return switchDetailsPanel(direction)
            }
            else -> return false
        }
    }

    private fun isInsideDetailsCarousel(screenX: Int, screenY: Int): Boolean {
        if (binding.elevationCard.visibility != View.VISIBLE) return false
        val bounds = Rect()
        return binding.elevationCard.getGlobalVisibleRect(bounds) && bounds.contains(screenX, screenY)
    }

    private fun switchDetailsPanel(direction: HorizontalSwipeDirection): Boolean {
        val visiblePanels = listOf(
            binding.detailsElevationButton,
            binding.detailsWayTypesButton,
            binding.detailsSurfacesButton,
        ).filter { it.visibility == View.VISIBLE }
        val currentIndex = visiblePanels.indexOfFirst { it.id == binding.detailsPanelToggle.checkedButtonId }
        if (currentIndex < 0) return false
        val targetIndex = when (direction) {
            HorizontalSwipeDirection.LEFT -> currentIndex + 1
            HorizontalSwipeDirection.RIGHT -> currentIndex - 1
        }
        val target = visiblePanels.getOrNull(targetIndex)?.id ?: return false
        binding.detailsPanelToggle.check(target)
        return true
    }

    private fun loadTour(reference: String) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stored = trackStore.listStoredTours().firstOrNull { it.reference == reference }
                        ?: error(getString(R.string.tour_not_found))
                    val storedTrack = trackStore.loadStoredTrack(reference)
                    val track = runCatching { ElevationEnricher().enrichIfMissing(storedTrack) }
                        .getOrDefault(storedTrack)
                    if (track.elevationSource != storedTrack.elevationSource) {
                        stored.file.writeText(GpxCodec.encode(track), Charsets.UTF_8)
                    }
                    LoadedTour(stored, track, TourInsightsAnalyzer.analyze(track))
                }
            }
            result.onSuccess(::renderTour)
                .onFailure {
                    Toast.makeText(
                        this@TourDetailActivity,
                        getString(R.string.tour_statistics_error, it.localizedMessage ?: getString(R.string.unknown_error)),
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
        }
    }

    private fun renderTour(loaded: LoadedTour) {
        loadedTour = loaded
        val planned = loaded.stored.origin == TrackStore.StoredTourOrigin.IMPORTED
        renderActionsMenu(loaded)
        queryOfflineStatus(loaded)
        binding.tourNameText.text = loaded.track.name
        binding.tourKindText.text = if (planned) {
            getString(
                if (loaded.stored.sourceReference != null) {
                    R.string.planned_tour_from_recording_hint
                } else {
                    R.string.planned_tour_forecast_hint
                },
            )
        } else {
            val recordedAt = loaded.track.points.firstNotNullOfOrNull { it.timeMillis }
                ?: loaded.stored.createdAtMillis
            getString(
                R.string.recorded_tour_hint_with_activity_and_time,
                getString((loaded.track.activityType ?: loaded.stored.activityType ?: de.wandern.app.model.ActivityType.HIKING).labelRes()),
                DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT,
                    displayLocale,
                ).format(Date(recordedAt)),
            )
        }
        binding.openMapButton.visibility = View.VISIBLE
        binding.fitnessProfileButton.visibility = if (planned) View.VISIBLE else View.GONE
        binding.fitnessProfileHint.visibility = if (planned) View.VISIBLE else View.GONE
        binding.openMapButton.setText(
            if (planned) R.string.start_tour else R.string.repeat_tour,
        )
        renderMap(loaded.track)
        if (planned) renderPlannedInsights(loaded.insights) else renderRecordedInsights(loaded.insights)
        renderElevation(loaded.insights)
        configureDetailsCarousel(loaded)
        binding.elevationAttributionText.visibility =
            if (loaded.track.elevationSource != null) View.VISIBLE else View.GONE
        if (loaded.track.elevationSource != null) {
            binding.elevationAttributionText.text = HtmlCompat.fromHtml(
                getString(R.string.elevation_data_attribution),
                HtmlCompat.FROM_HTML_MODE_LEGACY,
            )
            binding.elevationAttributionText.movementMethod = LinkMovementMethod.getInstance()
        }
        if (offerOfflineMapAfterLoad) {
            offerOfflineMapAfterLoad = false
            confirmDownloadOfflineMap(loaded)
        }
    }

    private fun configureDetailsCarousel(loaded: LoadedTour) {
        val hasRouteAttributes = loaded.track.routeAttributes.isNotEmpty()
        binding.detailsPanelToggle.visibility = if (hasRouteAttributes) View.VISIBLE else View.GONE
        binding.detailsWayTypesButton.visibility = if (hasRouteAttributes) View.VISIBLE else View.GONE
        binding.detailsSurfacesButton.visibility = if (hasRouteAttributes) View.VISIBLE else View.GONE
        binding.detailsPanelToggle.check(binding.detailsElevationButton.id)
        renderDetailsPanel(binding.detailsElevationButton.id)
    }

    private fun renderDetailsPanel(checkedId: Int) {
        val loaded = loadedTour ?: return
        when (checkedId) {
            binding.detailsWayTypesButton.id ->
                renderRouteAttributesPanel(loaded, RouteAttributesView.Dimension.WAY_TYPE)
            binding.detailsSurfacesButton.id ->
                renderRouteAttributesPanel(loaded, RouteAttributesView.Dimension.SURFACE)
            else -> {
                binding.detailsElevationPanel.visibility = View.VISIBLE
                binding.detailsRouteAttributesView.visibility = View.GONE
                binding.detailsPanelSummary.text = getString(
                    R.string.tour_map_elevation_summary,
                    formatDistance(loaded.insights.stats.distanceMeters),
                    formatMeters(loaded.insights.stats.ascentMeters),
                    formatMeters(loaded.insights.stats.descentMeters),
                )
            }
        }
    }

    private fun renderRouteAttributesPanel(
        loaded: LoadedTour,
        dimension: RouteAttributesView.Dimension,
    ) {
        val segments = loaded.track.routeAttributes
        val totalDistance = segments.sumOf { it.distanceMeters }
        val unknownDistance = segments.filter { segment ->
            when (dimension) {
                RouteAttributesView.Dimension.WAY_TYPE ->
                    segment.wayType == de.wandern.app.model.RouteWayType.UNKNOWN
                RouteAttributesView.Dimension.SURFACE ->
                    segment.surface == de.wandern.app.model.RouteSurface.UNKNOWN
            }
        }.sumOf { it.distanceMeters }
        val coverage = if (totalDistance > 0.0) {
            (((totalDistance - unknownDistance) / totalDistance) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }
        binding.detailsElevationPanel.visibility = View.GONE
        binding.detailsRouteAttributesView.visibility = View.VISIBLE
        binding.detailsPanelSummary.text = getString(
            R.string.route_attribute_coverage,
            formatDistance(totalDistance),
            coverage,
        )
        binding.detailsRouteAttributesView.setSegments(segments, dimension)
    }

    private fun renderPlannedInsights(insights: TourInsights) {
        val stats = insights.stats
        val fitnessLevel = fitnessPreferences.level
        val forecast = TourForecaster.forecast(stats, insights.elevationProfile, fitnessLevel)
        binding.fitnessProfileButton.text = getString(
            R.string.fitness_profile_button,
            getString(fitnessLevel.labelRes()),
        )

        binding.distanceValue.text = formatDistance(stats.distanceMeters)
        binding.distanceLabel.setText(R.string.distance_label)
        binding.durationValue.text = forecast?.let { formatDuration(it.totalDurationMillis) }
            ?: getString(R.string.not_available)
        binding.durationLabel.setText(R.string.forecast_total_time)
        binding.movingTimeValue.text = forecast?.let { formatDuration(it.movingDurationMillis) }
            ?: getString(R.string.not_available)
        binding.movingTimeLabel.setText(R.string.forecast_walking_time)
        binding.paceValue.text = forecast?.let { formatDuration(it.breakDurationMillis) }
            ?: getString(R.string.not_available)
        binding.paceLabel.setText(R.string.forecast_break_time)
        binding.averageSpeedValue.text = forecast?.let { formatPace(it.paceSecondsPerKilometer) }
            ?: getString(R.string.not_available)
        binding.averageSpeedLabel.setText(R.string.forecast_pace)
        binding.pointsValue.text = forecast?.let {
            String.format(displayLocale, "%.1f km/h", it.averageSpeedKilometersPerHour)
        } ?: getString(R.string.not_available)
        binding.pointsLabel.setText(R.string.forecast_speed)
        binding.ascentValue.text = formatMeters(stats.ascentMeters)
        binding.ascentLabel.setText(R.string.ascent)
        binding.descentValue.text = formatMeters(stats.descentMeters)
        binding.descentLabel.setText(R.string.descent)
        binding.speedCard.visibility = View.GONE
    }

    private fun showFitnessProfileDialog() {
        val levels = HikingFitnessLevel.entries.toTypedArray()
        val labels = levels.map { getString(it.optionLabelRes()) }.toTypedArray()
        val selected = levels.indexOf(fitnessPreferences.level)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fitness_profile_title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                fitnessPreferences.level = levels[index]
                loadedTour?.insights?.let(::renderPlannedInsights)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun HikingFitnessLevel.labelRes(): Int = when (this) {
        HikingFitnessLevel.LEISURELY -> R.string.fitness_leisurely
        HikingFitnessLevel.AVERAGE -> R.string.fitness_average
        HikingFitnessLevel.FIT -> R.string.fitness_fit
        HikingFitnessLevel.SPORTY -> R.string.fitness_sporty
    }

    private fun HikingFitnessLevel.optionLabelRes(): Int = when (this) {
        HikingFitnessLevel.LEISURELY -> R.string.fitness_leisurely_option
        HikingFitnessLevel.AVERAGE -> R.string.fitness_average_option
        HikingFitnessLevel.FIT -> R.string.fitness_fit_option
        HikingFitnessLevel.SPORTY -> R.string.fitness_sporty_option
    }

    private fun renderRecordedInsights(insights: TourInsights) {
        val stats = insights.stats
        binding.distanceValue.text = formatDistance(stats.distanceMeters)
        binding.distanceLabel.setText(R.string.distance_label)
        binding.durationValue.text = if (insights.hasTimeData) formatDuration(stats.durationMillis) else getString(R.string.not_available)
        binding.durationLabel.setText(R.string.total_time)
        binding.movingTimeValue.text = if (insights.hasTimeData) formatDuration(stats.movingDurationMillis) else getString(R.string.not_available)
        binding.movingTimeLabel.setText(R.string.moving_time)
        binding.paceValue.text = stats.paceSecondsPerKilometer?.let(::formatPace) ?: getString(R.string.not_available)
        binding.paceLabel.setText(R.string.average_pace)
        binding.averageSpeedValue.text = stats.averageSpeedMetersPerSecond.takeIf { insights.hasTimeData && it > 0.0 }
            ?.let { String.format(displayLocale, "%.1f km/h", it * 3.6) }
            ?: getString(R.string.not_available)
        binding.averageSpeedLabel.setText(R.string.average_speed)
        binding.pointsValue.text = if (insights.hasTimeData) {
            formatDuration(stats.pauseDurationMillis)
        } else {
            getString(R.string.not_available)
        }
        val pauseCount = resources.getQuantityString(
            R.plurals.pause_count,
            stats.pauseCount,
            stats.pauseCount,
        )
        binding.pointsLabel.text = getString(R.string.pause_time_with_count, pauseCount)
        binding.ascentValue.text = formatMeters(stats.ascentMeters)
        binding.ascentLabel.setText(R.string.ascent)
        binding.descentValue.text = formatMeters(stats.descentMeters)
        binding.descentLabel.setText(R.string.descent)
        binding.speedCard.visibility = View.VISIBLE
        binding.speedChart.setSeries(
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

    private fun renderElevation(insights: TourInsights) {
        binding.elevationChart.setSeries(
            samples = insights.elevationProfile,
            unit = "m",
            color = Color.parseColor("#1E4D3C"),
            emptyMessage = getString(R.string.no_elevation_data),
            minimumValueRange = 100.0,
            colorBySlope = true,
            selectionFormatter = ::formatElevationSelection,
        )
    }

    private fun selectProfileDistance(distanceMeters: Double?) {
        binding.elevationChart.setSelectedDistance(distanceMeters)
        if (binding.speedCard.visibility == View.VISIBLE) {
            binding.speedChart.setSelectedDistance(distanceMeters)
        }
    }

    private fun formatElevationSelection(sample: de.wandern.app.model.ProfileSample): String {
        val slope = sample.secondaryValue?.let { String.format(displayLocale, "%+.1f %%", it) } ?: "—"
        return String.format(
            displayLocale,
            "%.2f km · %.0f m · %s",
            sample.distanceMeters / 1000.0,
            sample.value,
            slope,
        )
    }

    private fun formatSpeedSelection(sample: de.wandern.app.model.ProfileSample): String =
        String.format(
            displayLocale,
            "%.2f km · %.1f km/h",
            sample.distanceMeters / 1000.0,
            sample.value,
        )

    private fun renderMap(track: GpxTrack) {
        binding.previewMapView.getMapAsync { map ->
            map.addOnMapClickListener {
                loadedTour?.stored?.reference?.let(::openInteractiveMap)
                true
            }
            map.uiSettings.apply {
                isScrollGesturesEnabled = false
                isZoomGesturesEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true
                isLogoEnabled = false
                setAttributionGravity(Gravity.BOTTOM or Gravity.START)
                setAttributionMargins(
                    dp(MAP_ATTRIBUTION_MARGIN_DP),
                    dp(MAP_ATTRIBUTION_MARGIN_DP),
                    dp(MAP_ATTRIBUTION_MARGIN_DP),
                    dp(MAP_ATTRIBUTION_MARGIN_DP),
                )
            }
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                MapStyleLocalizer.localize(style, AppLanguage.forContext(this))
                val routeFeatures = track.segments.filter { it.size >= 2 }.map { segment ->
                    Feature.fromGeometry(
                        LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }),
                    )
                }
                style.addSource(GeoJsonSource(PREVIEW_ROUTE_SOURCE, FeatureCollection.fromFeatures(routeFeatures)))
                style.addLayer(
                    LineLayer(PREVIEW_ROUTE_LAYER, PREVIEW_ROUTE_SOURCE).withProperties(
                        lineColor(Color.parseColor("#1677FF")),
                        lineWidth(5f),
                        lineOpacity(0.9f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND),
                    ),
                )
                RouteDirectionIndicator.addToStyle(
                    context = this,
                    style = style,
                    sourceId = PREVIEW_ROUTE_SOURCE,
                    layerId = PREVIEW_ROUTE_DIRECTION_LAYER,
                    iconId = PREVIEW_ROUTE_DIRECTION_ICON,
                )
                addEndpointMarkers(map, track)
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
                    binding.previewMapView.post {
                        map.moveCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                bounds,
                                (28 * resources.displayMetrics.density).toInt(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun addEndpointMarkers(map: org.maplibre.android.maps.MapLibreMap, track: GpxTrack) {
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

    private fun renderActionsMenu(loaded: LoadedTour) {
        binding.toolbar.menu.clear()
        var order = 0
        if (loaded.stored.origin == TrackStore.StoredTourOrigin.RECORDED) {
            binding.toolbar.menu.add(0, MENU_PLAN_FROM_RECORDING, order++, R.string.plan_from_recording)
        } else {
            binding.toolbar.menu.add(0, MENU_EDIT, order++, R.string.edit_tour)
            binding.toolbar.menu.add(0, MENU_DUPLICATE, order++, R.string.duplicate_tour)
        }
        binding.toolbar.menu.add(0, MENU_RENAME, order++, R.string.rename_tour)
        binding.toolbar.menu.add(0, MENU_EXPORT, order++, R.string.export_gpx)
        when (offlineMapStatus.availability) {
            OfflineMapAvailability.NOT_DOWNLOADED ->
                binding.toolbar.menu.add(0, MENU_DOWNLOAD_OFFLINE_MAP, order++, R.string.offline_map_save)
            OfflineMapAvailability.PARTIAL -> {
                binding.toolbar.menu.add(0, MENU_DOWNLOAD_OFFLINE_MAP, order++, R.string.offline_map_continue)
                binding.toolbar.menu.add(0, MENU_DELETE_OFFLINE_MAP, order++, R.string.offline_map_delete)
            }
            OfflineMapAvailability.DOWNLOADED ->
                binding.toolbar.menu.add(0, MENU_DELETE_OFFLINE_MAP, order++, R.string.offline_map_delete)
            OfflineMapAvailability.ERROR ->
                binding.toolbar.menu.add(0, MENU_RETRY_OFFLINE_STATUS, order++, R.string.retry)
            OfflineMapAvailability.CHECKING -> Unit
        }
        binding.toolbar.menu.add(0, MENU_DELETE, order, R.string.delete)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> editTour(loaded.stored.reference)
                MENU_DUPLICATE -> duplicateTour(loaded)
                MENU_PLAN_FROM_RECORDING -> planFromRecording(loaded)
                MENU_RENAME -> showRenameDialog(loaded)
                MENU_EXPORT -> {
                    exportSource = loaded.stored.file
                    exportLauncher.launch(exportFileName(loaded.stored.name))
                }
                MENU_DOWNLOAD_OFFLINE_MAP -> confirmDownloadOfflineMap(loaded)
                MENU_DELETE_OFFLINE_MAP -> confirmDeleteOfflineMap(loaded)
                MENU_RETRY_OFFLINE_STATUS -> queryOfflineStatus(loaded)
                MENU_DELETE -> confirmDeleteTour(loaded)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun editTour(reference: String) {
        startActivity(
            Intent(this, RoutePlannerActivity::class.java)
                .putExtra(RoutePlannerActivity.EXTRA_EDIT_TOUR_REFERENCE, reference),
        )
        finish()
    }

    private fun duplicateTour(loaded: LoadedTour) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    trackStore.duplicateImportedTrack(loaded.stored.reference)
                }
            }
            result.onSuccess { copy ->
                toast(getString(R.string.tour_duplicated))
                editTour(copy.reference)
            }.onFailure { error ->
                toast(getString(R.string.tour_duplicate_error, error.localizedMessage ?: getString(R.string.not_available)))
            }
        }
    }

    private fun planFromRecording(loaded: LoadedTour) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    trackStore.saveRouteDefinitionFromRecording(loaded.stored.reference)
                }
            }
            result.onSuccess { planned ->
                toast(getString(R.string.recording_added_to_planned))
                startActivity(
                    Intent(this@TourDetailActivity, TourDetailActivity::class.java)
                        .putExtra(EXTRA_TOUR_REFERENCE, planned.reference),
                )
                finish()
            }.onFailure { error ->
                toast(getString(R.string.plan_from_recording_error, error.localizedMessage ?: getString(R.string.not_available)))
            }
        }
    }

    private fun showRenameDialog(loaded: LoadedTour) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(loaded.stored.name)
            selectAll()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(DIALOG_FIELD_HORIZONTAL_PADDING_DP), 0, dp(DIALOG_FIELD_HORIZONTAL_PADDING_DP), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_tour_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> renameTour(loaded, input.text.toString()) }
            .show()
    }

    private fun renameTour(loaded: LoadedTour, requestedName: String) {
        if (requestedName.isBlank()) {
            toast(getString(R.string.tour_rename_error, getString(R.string.tour_name_empty_error)))
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    trackStore.renameStoredTour(loaded.stored.reference, requestedName)
                }
            }
            result.onSuccess { renamed ->
                if (renamed) {
                    toast(getString(R.string.tour_renamed))
                    loadTour(loaded.stored.reference)
                } else {
                    toast(getString(R.string.tour_rename_error, getString(R.string.tour_not_found)))
                }
            }.onFailure { error ->
                toast(getString(R.string.tour_rename_error, error.localizedMessage ?: getString(R.string.not_available)))
            }
        }
    }

    private fun exportFileName(tourName: String): String {
        val safeName = tourName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "Wanderung" }
        return if (safeName.endsWith(".gpx", ignoreCase = true)) safeName else "$safeName.gpx"
    }

    private fun exportTrack(source: File, target: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(target, "w")?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error(getString(R.string.target_file_open_error))
                }
            }
            result.onSuccess { toast(getString(R.string.gpx_exported)) }
                .onFailure { error ->
                    toast(getString(R.string.gpx_export_error, error.localizedMessage ?: getString(R.string.not_available)))
                }
        }
    }

    private fun queryOfflineStatus(loaded: LoadedTour) {
        offlineMapStatus = OfflineMapStatus(OfflineMapAvailability.CHECKING)
        renderActionsMenu(loaded)
        offlineMapDownloader.status(loaded.track) { status ->
            runOnUiThread {
                if (isDestroyed || loadedTour?.stored?.reference != loaded.stored.reference) return@runOnUiThread
                offlineMapStatus = status
                renderActionsMenu(loaded)
            }
        }
    }

    private fun confirmDownloadOfflineMap(loaded: LoadedTour) {
        val plan = runCatching { OfflineMapPlanner.plan(loaded.track) }.getOrElse { error ->
            toast(getString(R.string.offline_map_error, error.localizedMessage ?: getString(R.string.not_available)))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_map_question_title)
            .setMessage(getString(R.string.offline_map_question_message, plan.maxZoom, plan.estimatedTileCount))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.offline_map_download) { _, _ -> downloadOfflineMap(loaded) }
            .show()
    }

    private fun downloadOfflineMap(loaded: LoadedTour) {
        offlineMapDownloader.download(loaded.track) { state ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when (state) {
                    is OfflineMapDownloadState.Complete -> {
                        toast(getString(R.string.offline_map_saved))
                        queryOfflineStatus(loaded)
                    }
                    is OfflineMapDownloadState.Error -> {
                        toast(getString(R.string.offline_map_error, state.message))
                        queryOfflineStatus(loaded)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun confirmDeleteOfflineMap(loaded: LoadedTour) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_map_delete_title)
            .setMessage(getString(R.string.offline_map_delete_message, loaded.stored.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.offline_map_delete) { _, _ ->
                offlineMapDownloader.delete(loaded.track) { result ->
                    runOnUiThread {
                        result.onSuccess {
                            toast(getString(R.string.offline_map_deleted))
                            queryOfflineStatus(loaded)
                        }.onFailure { error ->
                            toast(getString(R.string.offline_map_error, error.localizedMessage ?: getString(R.string.not_available)))
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteTour(loaded: LoadedTour) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tour_delete_title)
            .setMessage(getString(R.string.tour_delete_message, loaded.stored.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteTour(loaded) }
            .show()
    }

    private fun deleteTour(loaded: LoadedTour) {
        offlineMapDownloader.delete(loaded.track) { mapResult ->
            runOnUiThread {
                mapResult.onFailure { error ->
                    toast(getString(R.string.tour_delete_error, error.localizedMessage ?: getString(R.string.offline_map_label)))
                    return@runOnUiThread
                }
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        trackStore.deleteStoredTour(loaded.stored.reference)
                    }
                    if (deleted) {
                        toast(getString(R.string.tour_deleted))
                        finish()
                    } else {
                        toast(getString(R.string.tour_delete_error, getString(R.string.tour_not_found)))
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun openOnMainMap(reference: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TOUR_REFERENCE, reference)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun openInteractiveMap(reference: String) {
        startActivity(
            Intent(this, TourMapActivity::class.java)
                .putExtra(TourMapActivity.EXTRA_TOUR_REFERENCE, reference),
        )
    }

    private fun startTour(loaded: LoadedTour) {
        if (loaded.stored.origin == TrackStore.StoredTourOrigin.IMPORTED) {
            openOnMainMap(loaded.stored.reference)
            return
        }
        binding.openMapButton.isEnabled = false
        binding.openMapButton.setText(R.string.preparing_tour)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    trackStore.saveRouteDefinitionFromRecording(loaded.stored.reference)
                }
            }
            result.onSuccess { planned ->
                openOnMainMap(planned.reference)
            }.onFailure {
                binding.openMapButton.isEnabled = true
                binding.openMapButton.setText(R.string.repeat_tour)
                Toast.makeText(
                    this@TourDetailActivity,
                    getString(R.string.plan_from_recording_error, it.localizedMessage ?: getString(R.string.unknown_error)),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun formatDistance(distanceMeters: Double) =
        String.format(displayLocale, "%.2f km", distanceMeters / 1000.0)

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

    override fun onStart() { super.onStart(); binding.previewMapView.onStart() }
    override fun onResume() { super.onResume(); binding.previewMapView.onResume() }
    override fun onPause() { binding.previewMapView.onPause(); super.onPause() }
    override fun onStop() { binding.previewMapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.previewMapView.onLowMemory() }
    override fun onDestroy() { binding.previewMapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.previewMapView.onSaveInstanceState(outState)
    }

    private data class LoadedTour(
        val stored: TrackStore.StoredTour,
        val track: GpxTrack,
        val insights: TourInsights,
    )

    companion object {
        const val EXTRA_TOUR_REFERENCE = "de.wandern.app.TOUR_DETAIL_REFERENCE"
        const val EXTRA_OFFER_OFFLINE_MAP = "de.wandern.app.TOUR_DETAIL_OFFER_OFFLINE_MAP"
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val PREVIEW_ROUTE_SOURCE = "preview-route-source"
        private const val PREVIEW_ROUTE_LAYER = "preview-route-layer"
        private const val PREVIEW_ROUTE_DIRECTION_LAYER = "preview-route-direction-layer"
        private const val PREVIEW_ROUTE_DIRECTION_ICON = "preview-route-direction-icon"
        private const val CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS = 50.0
        private const val MAP_ATTRIBUTION_MARGIN_DP = 8
        private const val DIALOG_FIELD_HORIZONTAL_PADDING_DP = 24
        private const val MENU_EDIT = 1
        private const val MENU_DUPLICATE = 2
        private const val MENU_PLAN_FROM_RECORDING = 3
        private const val MENU_RENAME = 4
        private const val MENU_EXPORT = 5
        private const val MENU_DOWNLOAD_OFFLINE_MAP = 6
        private const val MENU_DELETE_OFFLINE_MAP = 7
        private const val MENU_RETRY_OFFLINE_STATUS = 8
        private const val MENU_DELETE = 9
        private const val DETAILS_SWIPE_MIN_DISTANCE_DP = 64f
        private const val DETAILS_SWIPE_HORIZONTAL_RATIO = 1.35f
        private const val DETAILS_SWIPE_MAX_DURATION_MILLIS = 450L
    }
}
