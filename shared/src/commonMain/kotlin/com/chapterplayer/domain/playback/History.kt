package com.chapterplayer.domain.playback

/**
 * Represents a single point in time where a user was listening to a specific book.
 */
data class HistoryEntry(val bookId: String, val positionMs: Long, val recordedAt: Long)

/**
 * Determines if the user jumped significantly from their previous position.
 * Returns the previous position if a jump occurred, otherwise null.
 */
fun jumpedFrom(
    previousPositionMs: Long,
    previousAtMs: Long,
    positionMs: Long,
    atMs: Long,
    speed: Float,
    isPlaying: Boolean,
    minJumpMs: Long = 60_000
): Long? {
    val elapsed = if (atMs > previousAtMs) atMs - previousAtMs else 0L
    val effectiveSpeed = if (speed <= 0f) 1.0f else speed
    
    val expectedPosition = if (isPlaying) {
        previousPositionMs + (elapsed * effectiveSpeed).toLong()
    } else {
        previousPositionMs
    }

    val diff = if (positionMs > expectedPosition) positionMs - expectedPosition else expectedPosition - positionMs
    return if (diff >= minJumpMs) previousPositionMs else null
}

/**
 * Adds a new entry to the history list, removing duplicates for the same book within a time window.
 * The list is sorted newest first and trimmed to the maximum size.
 */
fun addToHistory(
    existing: List<HistoryEntry>,
    entry: HistoryEntry,
    max: Int = 10,
    dedupeMs: Long = 5_000
): List<HistoryEntry> {
    // Same book and within dedupeMs of the new position: the new entry replaces it.
    val filtered = existing.filterNot {
        it.bookId == entry.bookId && kotlin.math.abs(it.positionMs - entry.positionMs) <= dedupeMs
    }
    // Prepend newest entry and take up to max
    val result = listOf(entry) + filtered
    return if (result.size > max) result.take(max) else result
}

/**
 * Converts a timestamp into a human-readable relative time string.
 */
fun historyAgoLabel(recordedAt: Long, nowMs: Long): String {
    val diff = nowMs - recordedAt
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} h ago"
        else -> "${diff / 86_400_000} d ago"
    }
}
