package de.wandern.app.ui

import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Shared behavior for map bottom drawers.
 *
 * The sheet itself remains edge-to-edge, while the collapsed peek height and the
 * content's bottom padding include the system navigation area. This keeps both
 * the handle and the final content row reachable with gesture and three-button navigation.
 */
class BottomDrawerController<V : View>(
    private val sheet: V,
    private val content: View,
    private val basePeekHeightPx: Int,
    initialState: Int = BottomSheetBehavior.STATE_EXPANDED,
    private val onSlide: (slideOffset: Float) -> Unit = {},
    private val onStableStateChanged: (state: Int) -> Unit = {},
) {
    val behavior: BottomSheetBehavior<V> = BottomSheetBehavior.from(sheet).apply {
        isDraggable = true
        isHideable = false
        skipCollapsed = false
        isFitToContents = true
        peekHeight = basePeekHeightPx
        state = initialState
    }

    private val initialContentBottomPadding = content.paddingBottom
    var safeBottomInsetPx: Int = 0
        private set

    init {
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                onSlide(slideOffset)
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (
                    newState != BottomSheetBehavior.STATE_DRAGGING &&
                    newState != BottomSheetBehavior.STATE_SETTLING
                ) {
                    onStableStateChanged(newState)
                }
            }
        })
    }

    fun applyWindowInsets(insets: WindowInsetsCompat) {
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
        val safeBottom = maxOf(
            systemBars.bottom,
            navigationBars.bottom,
            tappable.bottom,
            navigationBarHeightFallback(),
        )
        if (safeBottom == safeBottomInsetPx) return
        safeBottomInsetPx = safeBottom
        behavior.peekHeight = basePeekHeightPx + safeBottom
        content.updatePadding(bottom = initialContentBottomPadding + safeBottom)
        sheet.requestLayout()
    }

    fun toggle() {
        behavior.state = if (behavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    fun expand() {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun navigationBarHeightFallback(): Int {
        val resourceId = sheet.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId != 0) sheet.resources.getDimensionPixelSize(resourceId) else 0
    }
}
