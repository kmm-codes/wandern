package de.wandern.app.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Learns the fixed angular offset between the phone azimuth and a reliable GPS course.
 * GPS is only a calibration reference; it is never returned as the displayed heading.
 */
class WalkingCompassCalibrator(
    private val requiredDistanceMeters: Double = 20.0,
    private val requiredSamples: Int = 6,
    private val minimumSpeedMetersPerSecond: Float = 0.8f,
    private val maximumAccuracyMeters: Float = 20f,
    private val maximumCourseChangeDegrees: Float = 30f,
) {
    enum class State { WAITING_FOR_PHONE, WAITING_FOR_GPS, WALK_STRAIGHT, COLLECTING, READY }

    data class Progress(
        val state: State,
        val distanceMeters: Double = 0.0,
        val sampleCount: Int = 0,
        val offsetDegrees: Float? = null,
        val requiredDistanceMeters: Double = 0.0,
    )

    private val offsets = mutableListOf<Float>()
    private var firstCourseDegrees: Float? = null
    private var previousPoint: TrackPoint? = null
    private var distanceMeters = 0.0

    fun update(point: TrackPoint, phoneHeadingDegrees: Float?): Progress {
        if (phoneHeadingDegrees == null) return progress(State.WAITING_FOR_PHONE)
        val course = point.bearingDegrees
        val speed = point.speedMetersPerSecond
        val accuracy = point.accuracyMeters
        if (
            course == null || speed == null || speed < minimumSpeedMetersPerSecond ||
            accuracy == null || accuracy > maximumAccuracyMeters
        ) {
            return progress(State.WAITING_FOR_GPS)
        }

        val firstCourse = firstCourseDegrees
        if (firstCourse != null && HeadingSmoother.angularDistance(firstCourse, course) > maximumCourseChangeDegrees) {
            resetSamples()
            firstCourseDegrees = course
            previousPoint = point
            offsets += signedAngle(course - phoneHeadingDegrees)
            return progress(State.WALK_STRAIGHT)
        }

        if (firstCourse == null) firstCourseDegrees = course
        previousPoint?.let { previous ->
            val stepDistance = GeoMath.distanceMeters(previous, point)
            if (stepDistance in 0.5..50.0) distanceMeters += stepDistance
        }
        previousPoint = point
        offsets += signedAngle(course - phoneHeadingDegrees)

        val offset = circularMean(offsets)
        val ready = distanceMeters >= requiredDistanceMeters && offsets.size >= requiredSamples
        return progress(if (ready) State.READY else State.COLLECTING, offset)
    }

    private fun resetSamples() {
        offsets.clear()
        previousPoint = null
        distanceMeters = 0.0
    }

    private fun progress(state: State, offset: Float? = circularMean(offsets)) = Progress(
        state = state,
        distanceMeters = distanceMeters,
        sampleCount = offsets.size,
        offsetDegrees = offset,
        requiredDistanceMeters = requiredDistanceMeters,
    )

    private fun circularMean(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val x = values.sumOf { cos(Math.toRadians(it.toDouble())) }
        val y = values.sumOf { sin(Math.toRadians(it.toDouble())) }
        return signedAngle(Math.toDegrees(atan2(y, x)).toFloat())
    }

    private fun signedAngle(degrees: Float): Float = ((degrees + 540f) % 360f) - 180f
}
