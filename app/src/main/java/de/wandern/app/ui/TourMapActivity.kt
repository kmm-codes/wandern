package de.wandern.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import de.wandern.app.R
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.FitnessPreferences
import de.wandern.app.data.GpxCodec
import de.wandern.app.data.TrackStore
import de.wandern.app.databinding.ActivityTourMapBinding
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.ProfileSample
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

class TourMapActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTourMapBinding
    private lateinit var trackStore: TrackStore
    private lateinit var fitnessPreferences: FitnessPreferences
    private var loadedTour: LoadedTour? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityTourMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binding.mapView.onCreate(savedInstanceState)
        binding.toolbar.setNavigationContentDescription(R.string.cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.hideDataButton.setOnClickListener { setPanelVisible(false) }
        binding.showDataButton.setOnClickListener { setPanelVisible(true) }
        trackStore = TrackStore(this)
        fitnessPreferences = FitnessPreferences(this)

        val reference = intent.getStringExtra(EXTRA_TOUR_REFERENCE)
        if (reference == null) {
            finish()
            return
        }
        loadTour(reference)
    }

    private fun loadTour(reference: String) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stored = trackStore.listStoredTours().firstOrNull { it.reference == reference }
                        ?: error("Tour nicht gefunden")
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
                    getString(R.string.tour_statistics_error, it.localizedMessage ?: "Unbekannter Fehler"),
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
        binding.primaryPanelButton.setText(if (planned) R.string.elevation_profile else R.string.speed_profile)
        binding.secondaryPanelButton.setText(if (planned) R.string.tour_map_forecast else R.string.elevation_profile)
        binding.panelToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) renderPanel(checkedId == binding.primaryPanelButton.id)
        }
        binding.panelToggle.check(binding.primaryPanelButton.id)
        renderPanel(primary = true)
        renderMap(loaded.track)
    }

    private fun renderPanel(primary: Boolean) {
        val loaded = loadedTour ?: return
        val planned = loaded.stored.origin == TrackStore.StoredTourOrigin.IMPORTED
        when {
            planned && primary -> renderElevationPanel(loaded.insights)
            planned -> renderForecastPanel(loaded.insights)
            primary -> renderSpeedPanel(loaded.insights)
            else -> renderElevationPanel(loaded.insights)
        }
    }

    private fun renderSpeedPanel(insights: TourInsights) {
        val stats = insights.stats
        binding.panelTitle.setText(R.string.speed_profile)
        binding.panelSummary.text = getString(
            R.string.tour_map_recorded_summary,
            formatDistance(stats.distanceMeters),
            formatDuration(stats.movingDurationMillis),
            stats.averageSpeedMetersPerSecond.takeIf { insights.hasTimeData && it > 0.0 }
                ?.let { String.format(Locale.GERMANY, "%.1f km/h", it * 3.6) }
                ?: getString(R.string.not_available),
        )
        binding.profileChart.visibility = View.VISIBLE
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
        binding.panelTitle.setText(R.string.elevation_profile)
        binding.panelSummary.text = getString(
            R.string.tour_map_elevation_summary,
            formatDistance(stats.distanceMeters),
            formatMeters(stats.ascentMeters),
            formatMeters(stats.descentMeters),
        )
        binding.profileChart.visibility = View.VISIBLE
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
        binding.panelTitle.setText(R.string.tour_map_forecast)
        val forecast = TourForecaster.forecast(
            insights.stats,
            insights.elevationProfile,
            fitnessPreferences.level,
        )
        binding.profileChart.visibility = View.GONE
        binding.panelSummary.text = forecast?.let(::formatForecast)
            ?: getString(R.string.not_available)
    }

    private fun formatForecast(forecast: TourForecast): String = getString(
        R.string.tour_map_forecast_summary,
        formatDuration(forecast.totalDurationMillis),
        formatDuration(forecast.movingDurationMillis),
        formatDuration(forecast.breakDurationMillis),
        String.format(Locale.GERMANY, "%.1f km/h", forecast.averageSpeedKilometersPerHour),
        formatPace(forecast.paceSecondsPerKilometer),
    )

    private fun setPanelVisible(visible: Boolean) {
        binding.dataPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.showDataButton.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun renderMap(track: GpxTrack) {
        binding.mapView.getMapAsync { map ->
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
                addEndpointMarkers(map, track)
                fitRoute(map, track)
            }
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
        val slope = sample.secondaryValue?.let { String.format(Locale.GERMANY, "%+.1f %%", it) } ?: "—"
        return String.format(
            Locale.GERMANY,
            "%.2f km · %.0f m · %s",
            sample.distanceMeters / 1_000.0,
            sample.value,
            slope,
        )
    }

    private fun formatSpeedSelection(sample: ProfileSample): String = String.format(
        Locale.GERMANY,
        "%.2f km · %.1f km/h",
        sample.distanceMeters / 1_000.0,
        sample.value,
    )

    private fun formatDistance(distanceMeters: Double) =
        String.format(Locale.GERMANY, "%.2f km", distanceMeters / 1_000.0)

    private fun formatMeters(meters: Double) = String.format(Locale.GERMANY, "%.0f m", meters)

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) String.format(Locale.GERMANY, "%d:%02d h", hours, minutes)
        else String.format(Locale.GERMANY, "%d min", minutes)
    }

    private fun formatPace(secondsPerKilometer: Double): String {
        val seconds = secondsPerKilometer.toInt().coerceAtMost(99 * 60 + 59)
        return String.format(Locale.GERMANY, "%d:%02d min/km", seconds / 60, seconds % 60)
    }

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
        private const val CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS = 50.0
    }
}
