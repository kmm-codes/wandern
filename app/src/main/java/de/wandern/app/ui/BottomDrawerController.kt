package de.wandern.app.ui

import android.view.View
import android.view.ViewGroup
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

    /**
     * The stable state the last programmatic request is heading for, or [NO_PENDING_STATE]
     * once the sheet has reached a stable state or the user grabbed it.
     *
     * A request is not visible in [BottomSheetBehavior.getState] right away: the behavior
     * defers `startSettling` until after a pending layout pass, so the sheet still reports its
     * previous state for a frame.
     */
    private var pendingTargetState = NO_PENDING_STATE

    init {
        // Keep gestures on non-interactive gaps inside the visible sheet from
        // falling through to map views underneath. BottomSheetBehavior still
        // gets the first opportunity to intercept vertical drawer drags.
        sheet.isClickable = true
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                onSlide(slideOffset)
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_SETTLING -> Unit
                    // A drag settles wherever the user lets go, so a request made before the
                    // drag no longer describes where the sheet is heading.
                    BottomSheetBehavior.STATE_DRAGGING -> pendingTargetState = NO_PENDING_STATE
                    else -> {
                        pendingTargetState = NO_PENDING_STATE
                        onStableStateChanged(newState)
                    }
                }
            }
        })
    }

    fun applyWindowInsets(insets: WindowInsetsCompat) {
        cancelParentTopPadding()
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

    /**
     * Keeps a top padding on the sheet's parent out of the sheet's own position.
     *
     * BottomSheetBehavior derives every offset - collapsed, expanded and half expanded - from
     * the parent's full height, but CoordinatorLayout lays its children out inside the padding
     * box and the behavior then offsets the child from that origin. A top padding therefore
     * pushes the sheet down by exactly that amount on every layout pass, so the collapsed
     * drawer loses the padding from its peek and slides the compact header behind the
     * navigation bar, and a settle that ended on the right pixel is snapped away again by the
     * next layout. Both drawer screens hand the status bar inset to the parent as top padding,
     * so the sheet cancels it with a matching negative margin and stays in the coordinate space
     * the behavior computes in.
     */
    private fun cancelParentTopPadding() {
        val parentTopPadding = (sheet.parent as? View)?.paddingTop ?: return
        val params = sheet.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin == -parentTopPadding) return
        params.topMargin = -parentTopPadding
        sheet.layoutParams = params
    }

    fun toggle() {
        if (isSettledOrMovingTo(BottomSheetBehavior.STATE_COLLAPSED)) {
            expand()
        } else {
            moveTo(BottomSheetBehavior.STATE_COLLAPSED)
        }
    }

    fun expand() {
        moveTo(BottomSheetBehavior.STATE_EXPANDED)
    }

    /** True while the sheet rests in [state] or is already on its way there. */
    fun isSettledOrMovingTo(state: Int): Boolean =
        if (pendingTargetState == NO_PENDING_STATE) behavior.state == state
        else pendingTargetState == state

    /**
     * Requests [state] unless this controller already has that exact request in flight.
     *
     * BottomSheetBehavior no longer ignores a redundant `state` assignment: every call runs
     * `startSettling`, which hands the sheet to `ViewDragHelper.smoothSlideViewTo`. That
     * captures the view, drops the active pointer and — whenever the sheet is not exactly at
     * the target top — restarts the settle animation with a fresh duration from the current
     * position. Asking a still settling drawer to expand therefore restarts an animation that
     * should not be running at all, which is visible as a flicker.
     *
     * The guard deliberately looks at [pendingTargetState] only and never at
     * [BottomSheetBehavior.getState]. A `setState` call is not visible in the reported state
     * right away - the behavior defers `startSettling` past a pending layout pass - so a sheet
     * that is about to leave a state still reports it, and skipping the request because of that
     * would silently drop the move. Requesting the state the sheet already rests in is harmless:
     * `smoothSlideViewTo` finds nothing to animate and only reasserts the state.
     */
    private fun moveTo(state: Int) {
        if (pendingTargetState == state) return
        pendingTargetState = state
        behavior.state = state
        // A request that only reasserts the state the sheet already reports settles nothing and
        // therefore never reaches the callback that clears the target again. Drop it right away
        // so a later move is not mistaken for a duplicate of a request that is long done.
        if (behavior.state == state) pendingTargetState = NO_PENDING_STATE
    }

    private fun navigationBarHeightFallback(): Int {
        val resourceId = sheet.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId != 0) sheet.resources.getDimensionPixelSize(resourceId) else 0
    }

    private companion object {
        const val NO_PENDING_STATE = -1
    }
}
