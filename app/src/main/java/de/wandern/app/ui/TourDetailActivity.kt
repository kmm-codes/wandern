package de.wandern.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.R
import de.wandern.app.data.TrackStore
import de.wandern.app.data.ElevationEnricher
import de.wandern.app.data.FitnessPreferences
import de.wandern.app.data.GpxCodec
import de.wandern.app.databinding.ActivityTourDetailBinding
import de.wandern.app.model.GeoMath
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.HikingFitnessLevel
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
import java.util.Locale

class TourDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTourDetailBinding
    private lateinit var trackStore: TrackStore
    private lateinit var fitnessPreferences: FitnessPreferences
    private var loadedTour: LoadedTour? = null

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
        trackStore = TrackStore(this)
        fitnessPreferences = FitnessPreferences(this)

        val reference = intent.getStringExtra(EXTRA_TOUR_REFERENCE)
        if (reference == null) {
            finish()
            return
        }
        binding.openMapButton.setOnClickListener { openOnMainMap(reference) }
        binding.fitnessProfileButton.setOnClickListener { showFitnessProfileDialog() }
        loadTour(reference)
    }

    private fun loadTour(reference: String) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stored = trackStore.listStoredTours().firstOrNull { it.reference == reference }
                        ?: error("Tour nicht gefunden")
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
                        getString(R.string.tour_statistics_error, it.localizedMessage ?: "Unbekannter Fehler"),
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
        }
    }

    private fun renderTour(loaded: LoadedTour) {
        loadedTour = loaded
        val planned = loaded.stored.origin == TrackStore.StoredTourOrigin.IMPORTED
        binding.tourNameText.text = loaded.track.name
        binding.tourKindText.setText(if (planned) R.string.planned_tour_forecast_hint else R.string.recorded_tour_actual_hint)
        binding.openMapButton.visibility = if (planned) View.VISIBLE else View.GONE
        binding.fitnessProfileButton.visibility = if (planned) View.VISIBLE else View.GONE
        binding.fitnessProfileHint.visibility = if (planned) View.VISIBLE else View.GONE
        if (planned) binding.openMapButton.setText(R.string.start_tour)
        renderMap(loaded.track)
        if (planned) renderPlannedInsights(loaded.insights) else renderRecordedInsights(loaded.insights)
        renderElevation(loaded.insights)
        binding.elevationAttributionText.visibility =
            if (loaded.track.elevationSource != null) View.VISIBLE else View.GONE
        if (loaded.track.elevationSource != null) {
            binding.elevationAttributionText.text = HtmlCompat.fromHtml(
                getString(R.string.elevation_data_attribution),
                HtmlCompat.FROM_HTML_MODE_LEGACY,
            )
            binding.elevationAttributionText.movementMethod = LinkMovementMethod.getInstance()
        }
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
            String.format(Locale.GERMANY, "%.1f km/h", it.averageSpeedKilometersPerHour)
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
            ?.let { String.format(Locale.GERMANY, "%.1f km/h", it * 3.6) }
            ?: getString(R.string.not_available)
        binding.averageSpeedLabel.setText(R.string.average_speed)
        binding.pointsValue.text = stats.pointCount.toString()
        binding.pointsLabel.setText(R.string.track_points)
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
        val slope = sample.secondaryValue?.let { String.format(Locale.GERMANY, "%+.1f %%", it) } ?: "—"
        return String.format(
            Locale.GERMANY,
            "%.2f km · %.0f m · %s",
            sample.distanceMeters / 1000.0,
            sample.value,
            slope,
        )
    }

    private fun formatSpeedSelection(sample: de.wandern.app.model.ProfileSample): String =
        String.format(
            Locale.GERMANY,
            "%.2f km · %.1f km/h",
            sample.distanceMeters / 1000.0,
            sample.value,
        )

    private fun renderMap(track: GpxTrack) {
        binding.previewMapView.getMapAsync { map ->
            map.uiSettings.apply {
                isScrollGesturesEnabled = false
                isZoomGesturesEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true
                isLogoEnabled = false
            }
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
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

    private fun openOnMainMap(reference: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TOUR_REFERENCE, reference)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun formatDistance(distanceMeters: Double) =
        String.format(Locale.GERMANY, "%.2f km", distanceMeters / 1000.0)

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
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val PREVIEW_ROUTE_SOURCE = "preview-route-source"
        private const val PREVIEW_ROUTE_LAYER = "preview-route-layer"
        private const val CIRCULAR_ROUTE_ENDPOINT_DISTANCE_METERS = 50.0
    }
}
