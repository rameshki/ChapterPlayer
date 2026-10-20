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

/**
 * Where a jump of [deltaMs] (negative goes back) from [offsetMs] in file [trackIndex] lands on the book's
 * timeline, which may be in another file. Clamped to the start and the end of the book; null for no tracks.
 */
fun seekByTarget(tracks: List<TrackSpan>, trackIndex: Int, offsetMs: Long, deltaMs: Long): TrackPosition? {
    val last = tracks.lastOrNull() ?: return null
    val total = last.startMs + last.durationMs
    val target = (bookPosition(tracks, trackIndex, offsetMs) + deltaMs).coerceIn(0L, total)
    return locate(tracks, target)
}
