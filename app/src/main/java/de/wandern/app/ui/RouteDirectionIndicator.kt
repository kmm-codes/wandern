package de.wandern.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconKeepUpright
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing

/** Adds sparse, subdued chevrons that make a route's travel direction visible. */
object RouteDirectionIndicator {
    fun addToStyle(
        context: Context,
        style: Style,
        sourceId: String,
        layerId: String,
        iconId: String,
    ) {
        style.addImage(iconId, createIcon(context))
        style.addLayer(
            SymbolLayer(layerId, sourceId).withProperties(
                symbolPlacement("line"),
                symbolSpacing(115f),
                iconImage(iconId),
                iconSize(0.65f),
                iconOpacity(0.68f),
                iconRotationAlignment("map"),
                iconKeepUpright(false),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
    }

    private fun createIcon(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (24 * density).toInt()
        val height = (14 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path().apply {
            moveTo(2f * density, 2f * density)
            lineTo(12f * density, height / 2f)
            lineTo(2f * density, height - 2f * density)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4.5f * density
            color = Color.WHITE
        }
        canvas.drawPath(path, paint)
        paint.strokeWidth = 2.2f * density
        paint.color = Color.parseColor("#1677FF")
        canvas.drawPath(path, paint)
        return bitmap
    }
}
