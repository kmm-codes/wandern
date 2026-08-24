package de.wandern.app.model

import kotlin.math.abs

/** Smooths compass headings across the 0°/360° boundary. */
class HeadingSmoother(
    private val smoothingFactor: Float = 0.22f,
) {
    private var heading: Float? = null

    fun update(measuredDegrees: Float): Float {
        val target = normalize(measuredDegrees)
        val previous = heading
        if (previous == null) {
            heading = target
            return target
        }
        val shortestDelta = ((target - previous + 540f) % 360f) - 180f
        return normalize(previous + shortestDelta * smoothingFactor).also { heading = it }
    }

    fun reset() {
        heading = null
    }

    companion object {
        fun angularDistance(firstDegrees: Float, secondDegrees: Float): Float =
            abs(((normalize(firstDegrees) - normalize(secondDegrees) + 540f) % 360f) - 180f)

        fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    }
}
