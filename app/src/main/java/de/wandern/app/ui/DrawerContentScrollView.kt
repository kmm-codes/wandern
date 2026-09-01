package de.wandern.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

/**
 * Scroll container whose content scrolling is enabled only after its bottom
 * drawer has reached the expanded state and the content actually overflows.
 * Drawer dragging remains available while content scrolling is disabled.
 */
class DrawerContentScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    var contentScrollingEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            isNestedScrollingEnabled = value
            if (!value && scrollY != 0) scrollTo(0, 0)
        }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        contentScrollingEnabled && super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean =
        contentScrollingEnabled && super.onTouchEvent(event)
}
