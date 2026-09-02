package de.wandern.app.model

/** Extends the in-memory recording shown on the map without reloading the database. */
object LiveTrackUpdate {
    fun append(
        track: GpxTrack,
        segmentIndex: Int,
        points: List<TrackPoint>,
    ): GpxTrack {
        if (segmentIndex < 0 || points.isEmpty()) return track
        val segments = track.segments.toMutableList()
        while (segments.size <= segmentIndex) segments.add(emptyList())
        segments[segmentIndex] = segments[segmentIndex] + points
        return track.copy(segments = segments)
    }
}
