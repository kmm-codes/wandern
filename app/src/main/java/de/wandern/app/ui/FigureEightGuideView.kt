package de.wandern.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import de.wandern.app.R

class FigureEightGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.moss_500)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.forest_700)
        style = Paint.Style.FILL
    }
    private val phoneDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val pathMeasure = PathMeasure()
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private var animationFraction = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3_200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationFraction = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        contentDescription = context.getString(R.string.compass_calibration_figure_eight_animation)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val centerX = width / 2f
        val centerY = height / 2f
        val halfWidth = width * 0.38f
        val halfHeight = height * 0.32f
        path.reset()
        path.moveTo(centerX, centerY)
        path.cubicTo(
            centerX + halfWidth * 0.55f, centerY - halfHeight,
            centerX + halfWidth, centerY - halfHeight,
            centerX + halfWidth, centerY,
        )
        path.cubicTo(
            centerX + halfWidth, centerY + halfHeight,
            centerX + halfWidth * 0.55f, centerY + halfHeight,
            centerX, centerY,
        )
        path.cubicTo(
            centerX - halfWidth * 0.55f, centerY - halfHeight,
            centerX - halfWidth, centerY - halfHeight,
            centerX - halfWidth, centerY,
        )
        path.cubicTo(
            centerX - halfWidth, centerY + halfHeight,
            centerX - halfWidth * 0.55f, centerY + halfHeight,
            centerX, centerY,
        )
        pathMeasure.setPath(path, true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, guidePaint)
        if (pathMeasure.length <= 0f) return
        pathMeasure.getPosTan(pathMeasure.length * animationFraction, position, tangent)
        val angle = Math.toDegrees(kotlin.math.atan2(tangent[1], tangent[0]).toDouble()).toFloat() + 90f
        canvas.save()
        canvas.translate(position[0], position[1])
        canvas.rotate(angle)
        val phoneWidth = 27f * density
        val phoneHeight = 48f * density
        canvas.drawRoundRect(
            RectF(-phoneWidth / 2, -phoneHeight / 2, phoneWidth / 2, phoneHeight / 2),
            6f * density,
            6f * density,
            phonePaint,
        )
        canvas.drawCircle(0f, phoneHeight * 0.34f, 2f * density, phoneDetailPaint)
        canvas.drawRoundRect(
            RectF(-5f * density, -phoneHeight * 0.38f, 5f * density, -phoneHeight * 0.34f),
            2f * density,
            2f * density,
            phoneDetailPaint,
        )
        canvas.restore()
    }
}
