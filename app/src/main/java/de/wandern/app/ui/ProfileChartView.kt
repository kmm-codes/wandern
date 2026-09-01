package de.wandern.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import de.wandern.app.R
import de.wandern.app.localization.AppLanguage
import de.wandern.app.model.ProfileSample
import kotlin.math.abs
import kotlin.math.max

class ProfileChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#301E4D3C")
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E4D3C")
        textSize = 11f * scaledDensity
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6D7F76")
        textSize = 13f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F26B38")
        strokeWidth = 2f * density
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F26B38")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val cursorCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE153A2E")
        style = Paint.Style.FILL
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var samples: List<ProfileSample> = emptyList()
    private var unit = ""
    private var emptyMessage = context.getString(R.string.no_data_available)
    private var includeZero = false
    private var minimumValueRange = 0.0
    private var colorBySlope = false
    private var selectionFormatter: ((ProfileSample) -> String)? = null
    private var chartContentDescription = ""
    private var selectedDistanceMeters: Double? = null
    private var progressDistanceMeters: Double? = null
    private var selectionActive = false
    private var longPressArmed = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotMaxDistance = 1.0
    private var bottomSystemOcclusionPx = 0
    var onSelectionChanged: ((Double?) -> Unit)? = null

    fun setBottomSystemOcclusion(occlusionPx: Int) {
        val normalized = occlusionPx.coerceAtLeast(0)
        if (normalized == bottomSystemOcclusionPx) return
        bottomSystemOcclusionPx = normalized
        invalidate()
    }

    private val activateSelection = Runnable {
        if (!longPressArmed || samples.size < 2) return@Runnable
        selectionActive = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        updateSelectionFromX(lastTouchX, notify = true)
    }

    init {
        isClickable = true
    }

    fun setSeries(
        samples: List<ProfileSample>,
        unit: String,
        color: Int,
        emptyMessage: String,
        includeZero: Boolean = false,
        minimumValueRange: Double = 0.0,
        colorBySlope: Boolean = false,
        selectionFormatter: ((ProfileSample) -> String)? = null,
    ) {
        this.samples = samples
        this.unit = unit
        this.emptyMessage = emptyMessage
        this.includeZero = includeZero
        this.minimumValueRange = minimumValueRange
        this.colorBySlope = colorBySlope
        this.selectionFormatter = selectionFormatter
        selectedDistanceMeters = null
        progressDistanceMeters = null
        linePaint.color = color
        fillPaint.color = Color.argb(38, Color.red(color), Color.green(color), Color.blue(color))
        chartContentDescription = emptyMessage.takeIf { samples.size < 2 }
            ?: context.getString(
                R.string.chart_content_description,
                samples.last().distanceMeters / 1000.0,
            )
        contentDescription = chartContentDescription
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) {
            canvas.drawText(emptyMessage, width / 2f, height / 2f, emptyPaint)
            return
        }

        val top = 14f * density
        val right = width - 12f * density
        val safeBottom = bottomSystemOcclusionPx.toFloat()
        val bottom = height - 28f * density - safeBottom
        val maxDistance = samples.maxOf { it.distanceMeters }.coerceAtLeast(1.0)
        var minValue = samples.minOf { it.value }
        var maxValue = samples.maxOf { it.value }
        if (includeZero) minValue = 0.0
        if (!includeZero && maxValue - minValue < minimumValueRange) {
            val center = (minValue + maxValue) / 2.0
            minValue = center - minimumValueRange / 2.0
            maxValue = center + minimumValueRange / 2.0
        }
        if (maxValue - minValue < 1.0) maxValue = minValue + 1.0
        val valuePadding = (maxValue - minValue) * 0.08
        if (!includeZero) minValue -= valuePadding
        maxValue += valuePadding
        val valueRange = maxValue - minValue
        val maxValueLabel = formatValue(maxValue)
        val minValueLabel = formatValue(minValue)
        val widestValueLabel = max(
            labelPaint.measureText(maxValueLabel),
            labelPaint.measureText(minValueLabel),
        )
        val left = max(48f * density, widestValueLabel + 10f * density)
        val chartWidth = max(1f, right - left)
        val chartHeight = max(1f, bottom - top)
        plotLeft = left
        plotRight = right
        plotMaxDistance = maxDistance

        repeat(3) { index ->
            val y = top + chartHeight * index / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        fun x(sample: ProfileSample) = left + (sample.distanceMeters / maxDistance * chartWidth).toFloat()
        fun y(sample: ProfileSample) = bottom - ((sample.value - minValue) / valueRange * chartHeight).toFloat()

        val path = Path().apply {
            moveTo(x(samples.first()), y(samples.first()))
            samples.drop(1).forEach { lineTo(x(it), y(it)) }
        }
        val fillPath = Path(path).apply {
            lineTo(x(samples.last()), bottom)
            lineTo(x(samples.first()), bottom)
            close()
        }
        canvas.drawPath(fillPath, fillPaint)
        if (colorBySlope) {
            samples.zipWithNext().forEach { (start, end) ->
                linePaint.color = slopeColor(
                    listOfNotNull(start.secondaryValue, end.secondaryValue).average().takeIf { !it.isNaN() },
                )
                canvas.drawLine(x(start), y(start), x(end), y(end), linePaint)
            }
        } else {
            canvas.drawPath(path, linePaint)
        }
        drawProgress(canvas, ::x, ::y)

        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(maxValueLabel, left - 7f * density, top + 4f * density, labelPaint)
        canvas.drawText(minValueLabel, left - 7f * density, bottom, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0 km", left, height - 7f * density - safeBottom, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            String.format(AppLanguage.forContext(context).locale, "%.1f km", maxDistance / 1000.0),
            right,
            height - 7f * density - safeBottom,
            labelPaint,
        )
        drawSelection(canvas, left, top, right, bottom, maxDistance, minValue, valueRange)
    }

    private fun drawProgress(
        canvas: Canvas,
        x: (ProfileSample) -> Float,
        y: (ProfileSample) -> Float,
    ) {
        val progressDistance = progressDistanceMeters ?: return
        if (samples.size < 2 || progressDistance <= samples.first().distanceMeters) return
        val clampedDistance = progressDistance.coerceAtMost(samples.last().distanceMeters)
        val progressPath = Path()
        var started = false
        samples.zipWithNext().forEach { (start, end) ->
            if (start.distanceMeters > clampedDistance) return@forEach
            if (!started) {
                progressPath.moveTo(x(start), y(start))
                started = true
            }
            if (end.distanceMeters <= clampedDistance) {
                progressPath.lineTo(x(end), y(end))
            } else if (end.distanceMeters > start.distanceMeters) {
                val fraction = ((clampedDistance - start.distanceMeters) /
                    (end.distanceMeters - start.distanceMeters)).coerceIn(0.0, 1.0)
                val interpolated = ProfileSample(
                    distanceMeters = clampedDistance,
                    value = start.value + (end.value - start.value) * fraction,
                )
                progressPath.lineTo(x(interpolated), y(interpolated))
            }
        }
        if (started) canvas.drawPath(progressPath, progressPaint)
    }

    private fun drawSelection(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        maxDistance: Double,
        minValue: Double,
        valueRange: Double,
    ) {
        val selectedDistance = selectedDistanceMeters ?: return
        val sample = samples.minByOrNull { kotlin.math.abs(it.distanceMeters - selectedDistance) } ?: return
        val x = left + (sample.distanceMeters / maxDistance * (right - left)).toFloat()
        val y = bottom - ((sample.value - minValue) / valueRange * (bottom - top)).toFloat()
        canvas.drawLine(x, top, x, bottom, cursorPaint)
        canvas.drawCircle(x, y, 7f * density, cursorCenterPaint)
        canvas.drawCircle(x, y, 4.5f * density, cursorPaint)

        val text = selectionFormatter?.invoke(sample) ?: return
        val horizontalPadding = 10f * density
        val tooltipWidth = tooltipTextPaint.measureText(text) + horizontalPadding * 2
        val tooltipHeight = 32f * density
        val tooltipLeft = (x - tooltipWidth / 2f).coerceIn(2f * density, width - tooltipWidth - 2f * density)
        val tooltipTop = if (y - tooltipHeight - 12f * density >= top) {
            y - tooltipHeight - 12f * density
        } else {
            (y + 12f * density).coerceAtMost(bottom - tooltipHeight)
        }
        val tooltipBounds = RectF(
            tooltipLeft,
            tooltipTop,
            tooltipLeft + tooltipWidth,
            tooltipTop + tooltipHeight,
        )
        canvas.drawRoundRect(tooltipBounds, 9f * density, 9f * density, tooltipPaint)
        val baseline = tooltipBounds.centerY() - (tooltipTextPaint.ascent() + tooltipTextPaint.descent()) / 2f
        canvas.drawText(text, tooltipBounds.centerX(), baseline, tooltipTextPaint)
    }

    fun setSelectedDistance(distanceMeters: Double?) {
        selectedDistanceMeters = distanceMeters
        if (distanceMeters != null) {
            samples.minByOrNull { abs(it.distanceMeters - distanceMeters) }
                ?.let { contentDescription = selectionFormatter?.invoke(it) ?: chartContentDescription }
        } else {
            contentDescription = chartContentDescription
        }
        invalidate()
    }

    fun setProgressDistance(distanceMeters: Double?, color: Int = Color.parseColor("#F26B38")) {
        progressDistanceMeters = distanceMeters
        progressPaint.color = color
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.size < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                selectionActive = false
                longPressArmed = true
                postDelayed(activateSelection, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                if (!selectionActive && abs(event.y - downY) > touchSlop) {
                    longPressArmed = false
                    removeCallbacks(activateSelection)
                }
                if (selectionActive) updateSelectionFromX(event.x, notify = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressArmed = false
                removeCallbacks(activateSelection)
                if (selectionActive && event.actionMasked == MotionEvent.ACTION_UP) {
                    updateSelectionFromX(event.x, notify = true)
                }
                if (selectionActive) onSelectionChanged?.invoke(null)
                parent?.requestDisallowInterceptTouchEvent(false)
                selectionActive = false
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(activateSelection)
        super.onDetachedFromWindow()
    }

    private fun updateSelectionFromX(x: Float, notify: Boolean) {
        if (plotRight <= plotLeft || samples.isEmpty()) return
        val fraction = ((x - plotLeft) / (plotRight - plotLeft)).coerceIn(0f, 1f)
        val targetDistance = fraction * plotMaxDistance
        val sample = samples.minByOrNull { kotlin.math.abs(it.distanceMeters - targetDistance) } ?: return
        selectedDistanceMeters = sample.distanceMeters
        contentDescription = selectionFormatter?.invoke(sample)
        if (notify) onSelectionChanged?.invoke(sample.distanceMeters)
        invalidate()
    }

    private fun formatValue(value: Double): String = when (unit) {
        "m" -> String.format(AppLanguage.forContext(context).locale, "%.0f m", value)
        else -> String.format(AppLanguage.forContext(context).locale, "%.1f %s", value, unit)
    }

    private fun slopeColor(slopePercent: Double?): Int {
        val steepness = kotlin.math.abs(slopePercent ?: 0.0)
        return when {
            steepness < 3.0 -> Color.parseColor("#3A9D5D")
            steepness < 7.0 -> Color.parseColor("#8AAA35")
            steepness < 11.0 -> Color.parseColor("#D4A62A")
            steepness < 16.0 -> Color.parseColor("#D7682F")
            steepness < 22.0 -> Color.parseColor("#B83A2F")
            else -> Color.parseColor("#7A1628")
        }
    }
}
