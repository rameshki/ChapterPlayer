package com.chapterplayer.data.model

/**
 * Locally tracked reading progress for a book.
 * Stored on the device; the progress syncer, the queue and the server merge keep it in step with the book's server.
 *
 * @param bookId       Matches [AudioBook.id].
 * @param positionMs   Last known playback position in milliseconds.
 * @param durationMs   Total book duration — stored here to avoid re-fetching for progress %.
 * @param lastPlayedAt Unix timestamp (ms) of last playback session.
 * @param isFinished   True once the user has reached the end.
 * @param mirrored     True when the row was copied from another copy of the same book on another server (see
 *                     `mirroredProgress`), not listened to on this copy itself.
 */
data class ReadingProgress(
    val bookId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val isFinished: Boolean = false,
    val mirrored: Boolean = false
) {
    /** Progress as a value from 0.0 to 1.0. */
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
