package de.wandern.app.model

object RecordingRetentionPolicy {
    private const val INLINE_DISCARD_LIMIT_METERS = 1_000.0

    fun canDiscardInline(distanceMeters: Double): Boolean =
        distanceMeters < INLINE_DISCARD_LIMIT_METERS
}
