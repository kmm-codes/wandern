package de.wandern.app.ui

import kotlin.math.abs

enum class HorizontalSwipeDirection {
    LEFT,
    RIGHT,
}

object HorizontalSwipeClassifier {
    fun classify(
        deltaX: Float,
        deltaY: Float,
        durationMillis: Long,
        minimumDistance: Float,
        horizontalRatio: Float = 1.25f,
        maximumDurationMillis: Long = 1_200L,
    ): HorizontalSwipeDirection? {
        if (
            durationMillis !in 1..maximumDurationMillis ||
            abs(deltaX) < minimumDistance ||
            abs(deltaX) <= abs(deltaY) * horizontalRatio
        ) {
            return null
        }
        return if (deltaX < 0f) HorizontalSwipeDirection.LEFT else HorizontalSwipeDirection.RIGHT
    }
}
