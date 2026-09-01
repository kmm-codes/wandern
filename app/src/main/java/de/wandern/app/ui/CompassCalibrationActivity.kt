package de.wandern.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.wandern.app.R
import de.wandern.app.databinding.ActivityCompassCalibrationBinding
import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.CompassCalibrationPrerequisite
import de.wandern.app.model.HeadingSmoother
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

class CompassCalibrationActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private enum class Screen { PREPARATION, STABILIZING, COMPLETE }

    private lateinit var binding: ActivityCompassCalibrationBinding
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private val prerequisite = CompassCalibrationPrerequisite()
    private val headingSmoother = HeadingSmoother()
    private val handler = Handler(Looper.getMainLooper())
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var rotationVectorSensor: Sensor? = null
    private var magneticFieldSensor: Sensor? = null
    private var rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var headingAccuracyDegrees: Float? = null
    private var latestPhoneHeadingDegrees: Float? = null
    private var latestLocation: Location? = null
    private var screen = Screen.PREPARATION
    private var resumed = false
    private var map: MapLibreMap? = null
    private var mapStyle: Style? = null
    private var mapCentered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityCompassCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapPreview.onCreate(savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.primaryButton.setOnClickListener { onPrimaryAction() }
        binding.secondaryButton.setOnClickListener { onSecondaryAction() }
        setupMapPreview()
        render()
    }

    override fun onResume() {
        super.onResume()
        binding.mapPreview.onResume()
        resumed = true
        registerCompassSensors()
        if (screen == Screen.STABILIZING) {
            prerequisite.confirmFigureEight(SystemClock.elapsedRealtime())
        }
        if (hasPreciseLocationPermission()) {
            startLocationUpdates()
        }
        render()
    }

    override fun onPause() {
        resumed = false
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        handler.removeCallbacksAndMessages(null)
        headingSmoother.reset()
        latestPhoneHeadingDegrees = null
        binding.mapPreview.onPause()
        super.onPause()
    }

    private fun onPrimaryAction() {
        when (screen) {
            Screen.PREPARATION -> confirmFigureEight()
            Screen.COMPLETE -> finish()
            Screen.STABILIZING -> Unit
        }
    }

    private fun onSecondaryAction() {
        when (screen) {
            Screen.STABILIZING, Screen.COMPLETE -> {
                prerequisite.restart()
                screen = Screen.PREPARATION
                render()
            }
            Screen.PREPARATION -> Unit
        }
    }

    private fun confirmFigureEight() {
        prerequisite.confirmFigureEight(SystemClock.elapsedRealtime())
        screen = Screen.STABILIZING
        headingSmoother.reset()
        latestPhoneHeadingDegrees = null
        rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        headingAccuracyDegrees = null
        sensorManager.unregisterListener(this)
        render()
        handler.postDelayed({
            if (!resumed || screen != Screen.STABILIZING) return@postDelayed
            prerequisite.confirmFigureEight(SystemClock.elapsedRealtime())
            registerCompassSensors()
            if (hasPreciseLocationPermission()) startLocationUpdates()
        }, SENSOR_RESTART_DELAY_MILLIS)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasPreciseLocationPermission()) return
        locationManager.removeUpdates(this)
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this)
    }

    private fun stopLocationUpdates() {
        locationManager.removeUpdates(this)
    }

    private fun registerCompassSensors() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magneticFieldSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
        latestLocation?.let { location ->
            heading += GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            ).declination
        }
        latestPhoneHeadingDegrees = headingSmoother.update(heading)
        headingAccuracyDegrees = event.values
            .getOrNull(4)
            ?.takeIf { it >= 0f }
            ?.let { Math.toDegrees(it.toDouble()).toFloat() }
        renderMapPreviewPosition()

        if (screen == Screen.STABILIZING) {
            val progress = prerequisite.onSensorSample(
                nowMillis = SystemClock.elapsedRealtime(),
                qualityConfirmed = isSensorQualityConfirmed(),
            )
            if (progress.state == CompassCalibrationPrerequisite.State.READY) {
                screen = Screen.COMPLETE
            }
            render()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magneticAccuracy = accuracy
            Sensor.TYPE_ROTATION_VECTOR -> rotationAccuracy = accuracy
        }
        render()
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = location
        renderMapPreviewPosition()
        if (!mapCentered) {
            mapCentered = true
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 17.0),
            )
        }
    }

    private fun isSensorQualityConfirmed(): Boolean {
        val sensorAccuracy = if (magneticFieldSensor != null) magneticAccuracy else rotationAccuracy
        return sensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
            headingAccuracyDegrees?.let { it <= MAX_HEADING_ACCURACY_DEGREES } == true
    }

    private fun sensorAccuracyLabel(): String = getString(
        when {
            rotationVectorSensor == null -> R.string.compass_sensor_missing
            isSensorQualityConfirmed() -> R.string.compass_accuracy_high
            magneticAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
                rotationAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW -> R.string.compass_accuracy_low
            else -> R.string.compass_accuracy_unreliable
        },
    )

    private fun render() {
        binding.figureEightGuide.visibility = View.GONE
        binding.gestureSymbol.visibility = View.VISIBLE
        binding.livePreviewHint.visibility = View.GONE
        binding.mapPreview.visibility = View.GONE
        binding.sensorStatusText.visibility = View.VISIBLE
        binding.sensorStatusText.text = getString(
            R.string.compass_calibration_sensor_status,
            sensorAccuracyLabel(),
        )
        binding.progressIndicator.visibility = View.GONE
        binding.progressIndicator.isIndeterminate = false
        binding.primaryButton.isEnabled = rotationVectorSensor != null
        binding.secondaryButton.visibility = View.GONE

        when (screen) {
            Screen.PREPARATION -> renderPreparation()
            Screen.STABILIZING -> renderStabilizing()
            Screen.COMPLETE -> renderComplete()
        }
    }

    private fun renderPreparation() {
        binding.figureEightGuide.visibility = View.VISIBLE
        binding.gestureSymbol.visibility = View.GONE
        binding.stepLabel.setText(R.string.compass_calibration_step_figure_eight)
        binding.gestureSymbol.text = "∞"
        binding.titleText.setText(R.string.compass_calibration_figure_eight_title)
        binding.messageText.setText(R.string.compass_calibration_figure_eight_message)
        binding.primaryButton.setText(R.string.compass_calibration_figure_eight_confirm)
    }

    private fun renderStabilizing() {
        showLiveMapPreview()
        binding.stepLabel.setText(R.string.compass_calibration_step_checking)
        binding.gestureSymbol.text = "⌁"
        binding.titleText.setText(R.string.compass_calibration_checking_title)
        binding.messageText.setText(R.string.compass_calibration_checking_message)
        binding.progressIndicator.visibility = View.VISIBLE
        binding.progressIndicator.isIndeterminate = true
        binding.primaryButton.setText(R.string.compass_calibration_checking_button)
        binding.primaryButton.isEnabled = false
        binding.secondaryButton.visibility = View.VISIBLE
        binding.secondaryButton.setText(R.string.compass_calibration_repeat_figure_eight)
    }

    private fun renderComplete() {
        showLiveMapPreview()
        binding.stepLabel.setText(R.string.compass_calibration_step_complete)
        binding.gestureSymbol.text = "✓"
        binding.titleText.setText(R.string.compass_calibration_complete_title)
        binding.messageText.setText(R.string.compass_calibration_complete_message)
        binding.primaryButton.setText(R.string.compass_calibration_done)
        binding.secondaryButton.visibility = View.VISIBLE
        binding.secondaryButton.setText(R.string.compass_calibration_repeat_figure_eight)
    }

    private fun showLiveMapPreview() {
        binding.livePreviewHint.visibility = View.VISIBLE
        binding.mapPreview.visibility = View.VISIBLE
        renderMapPreviewPosition()
    }

    private fun setupMapPreview() {
        binding.mapPreview.getMapAsync { readyMap ->
            map = readyMap.apply {
                uiSettings.isCompassEnabled = false
                uiSettings.isLogoEnabled = true
                uiSettings.isAttributionEnabled = true
            }
            readyMap.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                MapStyleLocalizer.localize(style, AppLanguage.forContext(this))
                mapStyle = style
                style.addSource(GeoJsonSource(PREVIEW_POSITION_SOURCE, EMPTY_FEATURE_COLLECTION))
                style.addLayer(
                    CircleLayer(PREVIEW_POSITION_HALO_LAYER, PREVIEW_POSITION_SOURCE).withProperties(
                        circleColor(Color.parseColor("#1677FF")),
                        circleRadius(16f),
                        circleOpacity(0.18f),
                    ),
                )
                style.addImage(PREVIEW_DIRECTION_ICON, createDirectionIcon())
                style.addSource(GeoJsonSource(PREVIEW_DIRECTION_SOURCE, EMPTY_FEATURE_COLLECTION))
                style.addLayer(
                    SymbolLayer(PREVIEW_DIRECTION_LAYER, PREVIEW_DIRECTION_SOURCE).withProperties(
                        iconImage(PREVIEW_DIRECTION_ICON),
                        iconRotate(0f),
                        iconRotationAlignment("map"),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                    ),
                )
                style.addLayer(
                    CircleLayer(PREVIEW_POSITION_LAYER, PREVIEW_POSITION_SOURCE).withProperties(
                        circleColor(Color.parseColor("#1677FF")),
                        circleRadius(9f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(3f),
                    ),
                )
                renderMapPreviewPosition()
            }
        }
    }

    private fun renderMapPreviewPosition() {
        val style = mapStyle ?: return
        val location = latestLocation ?: return
        val feature = FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)),
        )
        style.getSourceAs<GeoJsonSource>(PREVIEW_POSITION_SOURCE)?.setGeoJson(feature)
        val heading = latestPhoneHeadingDegrees ?: return
        style.getSourceAs<GeoJsonSource>(PREVIEW_DIRECTION_SOURCE)?.setGeoJson(feature)
        style.getLayerAs<SymbolLayer>(PREVIEW_DIRECTION_LAYER)?.setProperties(
            iconRotate(HeadingSmoother.normalize(heading)),
        )
    }

    private fun createDirectionIcon(): Bitmap {
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

    override fun onStart() {
        super.onStart()
        binding.mapPreview.onStart()
    }

    override fun onStop() {
        binding.mapPreview.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapPreview.onLowMemory()
    }

    override fun onDestroy() {
        binding.mapPreview.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapPreview.onSaveInstanceState(outState)
    }

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SENSOR_RESTART_DELAY_MILLIS = 350L
        private const val MAX_HEADING_ACCURACY_DEGREES = 25f
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val PREVIEW_POSITION_SOURCE = "compass-preview-position-source"
        private const val PREVIEW_POSITION_HALO_LAYER = "compass-preview-position-halo"
        private const val PREVIEW_POSITION_LAYER = "compass-preview-position"
        private const val PREVIEW_DIRECTION_SOURCE = "compass-preview-direction-source"
        private const val PREVIEW_DIRECTION_LAYER = "compass-preview-direction"
        private const val PREVIEW_DIRECTION_ICON = "compass-preview-direction-icon"
        private const val EMPTY_FEATURE_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
