package de.wandern.app.model

data class RecordingDurations(
    val totalMillis: Long,
    val movingMillis: Long,
) {
    val pauseMillis: Long get() = (totalMillis - movingMillis).coerceAtLeast(0L)
}

/**
 * Monotonic recording clock independent of GPS sample timestamps.
 *
 * Total time always advances while a session exists. Moving time advances only while reliable
 * motion evidence says that the user is moving. This keeps auto-pause from freezing elapsed time.
 */
class RecordingClock {
    private var totalAnchorElapsedRealtimeMillis: Long? = null
    private var totalBeforeAnchorMillis = 0L
    private var movingAnchorElapsedRealtimeMillis: Long? = null
    private var movingBeforeAnchorMillis = 0L

    val isMoving: Boolean get() = movingAnchorElapsedRealtimeMillis != null

    fun start(
        nowElapsedRealtimeMillis: Long,
        totalMillis: Long = 0L,
        movingMillis: Long = 0L,
        moving: Boolean = false,
    ) {
        totalBeforeAnchorMillis = totalMillis.coerceAtLeast(0L)
        movingBeforeAnchorMillis = movingMillis.coerceIn(0L, totalBeforeAnchorMillis)
        totalAnchorElapsedRealtimeMillis = nowElapsedRealtimeMillis
        movingAnchorElapsedRealtimeMillis = nowElapsedRealtimeMillis.takeIf { moving }
    }

    fun setMoving(moving: Boolean, nowElapsedRealtimeMillis: Long) {
        if (totalAnchorElapsedRealtimeMillis == null) return
        if (moving == isMoving) return
        if (moving) {
            movingAnchorElapsedRealtimeMillis = nowElapsedRealtimeMillis
        } else {
            val movingAnchor = movingAnchorElapsedRealtimeMillis ?: return
            movingBeforeAnchorMillis += (nowElapsedRealtimeMillis - movingAnchor).coerceAtLeast(0L)
            movingAnchorElapsedRealtimeMillis = null
        }
    }

    fun snapshot(nowElapsedRealtimeMillis: Long): RecordingDurations {
        val totalAnchor = totalAnchorElapsedRealtimeMillis
            ?: return RecordingDurations(0L, 0L)
        val total = totalBeforeAnchorMillis +
            (nowElapsedRealtimeMillis - totalAnchor).coerceAtLeast(0L)
        val moving = movingBeforeAnchorMillis + movingAnchorElapsedRealtimeMillis?.let { anchor ->
            (nowElapsedRealtimeMillis - anchor).coerceAtLeast(0L)
        }.let { it ?: 0L }
        return RecordingDurations(total, moving.coerceAtMost(total))
    }

    fun reset() {
        totalAnchorElapsedRealtimeMillis = null
        totalBeforeAnchorMillis = 0L
        movingAnchorElapsedRealtimeMillis = null
        movingBeforeAnchorMillis = 0L
    }

}

fun projectRecordingDurations(
    snapshot: TrackingSnapshot,
    nowElapsedRealtimeMillis: Long,
): RecordingDurations {
    val active = snapshot.state == RecordingState.RECORDING || snapshot.state == RecordingState.PAUSED
    if (!active || snapshot.capturedAtElapsedRealtimeMillis <= 0L) {
        return RecordingDurations(
            snapshot.stats.durationMillis,
            snapshot.stats.movingDurationMillis,
        )
    }
    val elapsed = (nowElapsedRealtimeMillis - snapshot.capturedAtElapsedRealtimeMillis)
        .coerceAtLeast(0L)
    val total = snapshot.stats.durationMillis + elapsed
    val moving = snapshot.stats.movingDurationMillis +
        elapsed.takeIf { snapshot.movementTimeRunning && snapshot.state == RecordingState.RECORDING }
            .let { it ?: 0L }
    return RecordingDurations(total, moving.coerceAtMost(total))
}
