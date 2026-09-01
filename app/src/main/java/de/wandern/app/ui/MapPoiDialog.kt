package de.wandern.app.ui

import android.graphics.RectF
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.wandern.app.R
import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.GeoMath
import de.wandern.app.model.MapPoiPresenter
import de.wandern.app.model.TrackPoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

object MapPoiDialog {
    fun show(
        activity: AppCompatActivity,
        map: MapLibreMap,
        coordinate: LatLng,
        userPosition: TrackPoint? = null,
        distanceFormatter: ((Double) -> String)? = null,
    ): Boolean {
        val screenPoint = map.projection.toScreenLocation(coordinate)
        val hitRadius = POI_TAP_RADIUS_DP * activity.resources.displayMetrics.density
        val feature = map.queryRenderedFeatures(
            RectF(
                screenPoint.x - hitRadius,
                screenPoint.y - hitRadius,
                screenPoint.x + hitRadius,
                screenPoint.y + hitRadius,
            ),
            *POI_LAYER_IDS,
        ).firstOrNull() ?: return false
        val language = AppLanguage.forContext(activity)
        val presentation = MapPoiPresenter.present(
            feature.firstStringProperty(*language.nameProperties.toTypedArray()),
            feature.firstStringProperty("class"),
            feature.firstStringProperty("subclass"),
            language,
        )
        val featurePoint = feature.geometry() as? Point
        val poiPosition = featurePoint?.let {
            TrackPoint(latitude = it.latitude(), longitude = it.longitude())
        } ?: TrackPoint(latitude = coordinate.latitude, longitude = coordinate.longitude)
        val details = buildList {
            if (presentation.title != presentation.category) add(presentation.category)
            if (userPosition != null && distanceFormatter != null) {
                add(
                    activity.getString(
                        R.string.map_poi_distance,
                        distanceFormatter(GeoMath.distanceMeters(userPosition, poiPosition)),
                    ),
                )
            }
            feature.firstStringProperty("level")?.let {
                add(activity.getString(R.string.map_poi_level, it))
            }
        }
        val content = activity.layoutInflater.inflate(R.layout.dialog_map_poi, null)
        content.findViewById<TextView>(R.id.poiDetailsText).apply {
            text = details.joinToString("\n")
            visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(presentation.title)
            .setView(content)
            .setPositiveButton(R.string.ok, null)
            .show()
        return true
    }

    private fun Feature.firstStringProperty(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            if (!hasProperty(key)) return@firstNotNullOfOrNull null
            runCatching { getStringProperty(key) }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
        }

    private val POI_LAYER_IDS = arrayOf("poi_r20", "poi_r7", "poi_r1", "poi_transit")
    private const val POI_TAP_RADIUS_DP = 22f
}
