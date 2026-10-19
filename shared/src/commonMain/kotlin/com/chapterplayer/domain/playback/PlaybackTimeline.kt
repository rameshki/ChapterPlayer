package com.chapterplayer.domain.playback

/** Represents a segment of audio within a larger timeline. */
data class TrackSpan(val index: Int, val startMs: Long, val durationMs: Long)
/** Represents a specific playback position within a track. */
data class TrackPosition(val trackIndex: Int, val offsetMs: Long)
/** Finds the track and offset corresponding to a given global position. */
fun locate(tracks: List<TrackSpan>, positionMs: Long): TrackPosition? {
    if (tracks.isEmpty()) return null

    // Find the last track that started at or before the given position
    val targetIndex = tracks.indexOfLast { it.startMs <= positionMs }

    // If no track started before position, or position is before the first track, return start of first track
    if (targetIndex == -1) {
        return TrackPosition(0, 0)
    }

    val track = tracks[targetIndex]
    // Calculate offset and clamp to the track's duration
    val offset = (positionMs - track.startMs).coerceIn(0, track.durationMs)
    
    return TrackPosition(targetIndex, offset)
}

/** Calculates the global book position from a track index and its internal offset. */
fun bookPosition(tracks: List<TrackSpan>, trackIndex: Int, offsetMs: Long): Long {
    val track = tracks.getOrNull(trackIndex) ?: return 0L
    
    // Calculate position and clamp the offset to the valid 0..duration range
    val validOffset = offsetMs.coerceIn(0, track.durationMs)
    return track.startMs + validOffset
}
