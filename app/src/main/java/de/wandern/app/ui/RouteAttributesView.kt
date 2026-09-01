package de.wandern.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import de.wandern.app.R
import de.wandern.app.model.RouteAttributeSegment
import de.wandern.app.model.RouteSurface
import de.wandern.app.model.RouteWayType
import java.util.Locale
import kotlin.math.roundToInt

class RouteAttributesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    enum class Dimension { WAY_TYPE, SURFACE }

    private val bar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        background = roundedBackground(Color.parseColor("#E2DED3"), 8f)
        clipToOutline = true
    }
    private val legend = LinearLayout(context).apply { orientation = VERTICAL }
    private val initialBottomPadding = paddingBottom
    private var bottomSystemOcclusionPx = 0

    init {
        orientation = VERTICAL
        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(28)).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            },
        )
        addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(legend, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    fun setSegments(segments: List<RouteAttributeSegment>, dimension: Dimension) {
        bar.removeAllViews()
        legend.removeAllViews()
        if (segments.isEmpty()) return

        val runs = buildList<Pair<Any, Double>> {
            segments.forEach { segment ->
                val key = keyFor(segment, dimension)
                val previous = lastOrNull()
                if (previous != null && previous.first == key) {
                    this[lastIndex] = key to (previous.second + segment.distanceMeters)
                } else {
                    add(key to segment.distanceMeters)
                }
            }
        }
        runs.forEachIndexed { index, (key, distanceMeters) ->
            val color = colorFor(key, dimension)
            bar.addView(
                View(context).apply {
                    setBackgroundColor(color)
                    contentDescription = labelFor(key, dimension)
                },
                LayoutParams(0, LayoutParams.MATCH_PARENT, distanceMeters.toFloat()).apply {
                    if (index < runs.lastIndex) marginEnd = dp(1)
                },
            )
        }

        val grouped = segments
            .groupBy { keyFor(it, dimension) }
            .mapValues { (_, values) -> values.sumOf(RouteAttributeSegment::distanceMeters) }
        orderedKeys(dimension).forEach { key ->
            val distance = grouped[key] ?: return@forEach
            legend.addView(createLegendRow(key, distance, dimension))
        }
        contentDescription = orderedKeys(dimension).mapNotNull { key ->
            grouped[key]?.let { "${labelFor(key, dimension)} ${formatDistance(it)}" }
        }.joinToString(", ")
    }

    fun setBottomSystemOcclusion(occlusionPx: Int) {
        val normalized = occlusionPx.coerceAtLeast(0)
        if (normalized == bottomSystemOcclusionPx) return
        bottomSystemOcclusionPx = normalized
        setPadding(paddingLeft, paddingTop, paddingRight, initialBottomPadding + normalized)
    }

    private fun createLegendRow(key: Any, distanceMeters: Double, dimension: Dimension): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(25)

            addView(
                View(context).apply {
                    background = roundedBackground(colorFor(key, dimension), 3f)
                },
                LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(10) },
            )
            addView(
                TextView(context).apply {
                    text = labelFor(key, dimension)
                    setTextColor(ContextCompat.getColor(context, R.color.forest_900))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextView(context).apply {
                    text = formatDistance(distanceMeters)
                    setTextColor(ContextCompat.getColor(context, R.color.forest_700))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }

    private fun keyFor(segment: RouteAttributeSegment, dimension: Dimension): Any = when (dimension) {
        Dimension.WAY_TYPE -> segment.wayType
        Dimension.SURFACE -> segment.surface
    }

    private fun orderedKeys(dimension: Dimension): List<Any> = when (dimension) {
        Dimension.WAY_TYPE -> RouteWayType.entries
        Dimension.SURFACE -> RouteSurface.entries
    }

    private fun labelFor(key: Any, dimension: Dimension): String = when (dimension) {
        Dimension.WAY_TYPE -> when (key as RouteWayType) {
            RouteWayType.MOUNTAIN_TRAIL -> context.getString(R.string.route_way_mountain_trail)
            RouteWayType.HIKING_TRAIL -> context.getString(R.string.route_way_hiking_trail)
            RouteWayType.TRACK -> context.getString(R.string.route_way_track)
            RouteWayType.FOOTWAY -> context.getString(R.string.route_way_footway)
            RouteWayType.MINOR_ROAD -> context.getString(R.string.route_way_minor_road)
            RouteWayType.ROAD -> context.getString(R.string.route_way_road)
            RouteWayType.UNKNOWN -> context.getString(R.string.route_attribute_unknown)
        }
        Dimension.SURFACE -> when (key as RouteSurface) {
            RouteSurface.ASPHALT -> context.getString(R.string.route_surface_asphalt)
            RouteSurface.PAVED -> context.getString(R.string.route_surface_paved)
            RouteSurface.COMPACTED -> context.getString(R.string.route_surface_compacted)
            RouteSurface.GRAVEL -> context.getString(R.string.route_surface_gravel)
            RouteSurface.NATURAL -> context.getString(R.string.route_surface_natural)
            RouteSurface.UNKNOWN -> context.getString(R.string.route_attribute_unknown)
        }
    }

    private fun colorFor(key: Any, dimension: Dimension): Int = Color.parseColor(
        when (dimension) {
            Dimension.WAY_TYPE -> when (key as RouteWayType) {
                RouteWayType.MOUNTAIN_TRAIL -> "#8A6C45"
                RouteWayType.HIKING_TRAIL -> "#B49363"
                RouteWayType.TRACK -> "#879071"
                RouteWayType.FOOTWAY -> "#B9C1C8"
                RouteWayType.MINOR_ROAD -> "#D1C8BA"
                RouteWayType.ROAD -> "#77808A"
                RouteWayType.UNKNOWN -> "#242424"
            }
            Dimension.SURFACE -> when (key as RouteSurface) {
                RouteSurface.ASPHALT -> "#77818D"
                RouteSurface.PAVED -> "#C9CDD1"
                RouteSurface.COMPACTED -> "#B89A70"
                RouteSurface.GRAVEL -> "#D0B889"
                RouteSurface.NATURAL -> "#9AA67B"
                RouteSurface.UNKNOWN -> "#242424"
            }
        },
    )

    private fun formatDistance(distanceMeters: Double): String = if (distanceMeters < 1_000.0) {
        context.getString(R.string.route_attribute_meters, distanceMeters.roundToInt())
    } else {
        context.getString(
            R.string.route_attribute_kilometers,
            String.format(Locale.getDefault(), "%.2f", distanceMeters / 1_000.0),
        )
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
}
