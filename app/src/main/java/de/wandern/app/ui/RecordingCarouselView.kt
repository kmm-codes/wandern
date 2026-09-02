package de.wandern.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** A small, dependency-free two-page carousel with deterministic page snapping. */
class RecordingCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    var onPageChanged: (Int) -> Unit = {}
    var currentPage: Int = 0
        private set

    private var downX = 0f
    private var gestureStartPage = 0

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            gestureStartPage = currentPage
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val pages = getChildAt(0) as? LinearLayout ?: return
        for (index in 0 until pages.childCount) {
            pages.getChildAt(index).layoutParams = pages.getChildAt(index).layoutParams.apply {
                this.width = width
            }
        }
        pages.requestLayout()
        post { scrollTo(currentPage * width, 0) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                gestureStartPage = currentPage
            }
            MotionEvent.ACTION_UP -> {
                val distance = event.x - downX
                val targetPage = if (kotlin.math.abs(distance) > width * PAGE_CHANGE_THRESHOLD) {
                    gestureStartPage + if (distance < 0f) 1 else -1
                } else {
                    (scrollX.toFloat() / width.coerceAtLeast(1)).roundToInt()
                }
                showPage(targetPage.coerceIn(0, pageCount() - 1), animate = true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                showPage(
                    (scrollX.toFloat() / width.coerceAtLeast(1)).roundToInt(),
                    animate = true,
                )
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun showPage(page: Int, animate: Boolean = true) {
        val target = page.coerceIn(0, pageCount() - 1)
        currentPage = target
        if (animate) smoothScrollTo(target * width, 0) else scrollTo(target * width, 0)
        onPageChanged(target)
    }

    private fun pageCount(): Int = (getChildAt(0) as? LinearLayout)?.childCount?.coerceAtLeast(1) ?: 1

    private companion object {
        const val PAGE_CHANGE_THRESHOLD = 0.12f
    }
}
