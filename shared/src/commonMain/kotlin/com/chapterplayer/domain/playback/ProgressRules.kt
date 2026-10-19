package com.chapterplayer.domain.playback

/** A book counts as finished when this little is left of it. */
const val FINISHED_REMAINING_MS = 5_000L

/** True when [positionMs] is within [FINISHED_REMAINING_MS] of the end of a book that is [durationMs] long. */
fun isFinishedAt(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs >= durationMs - FINISHED_REMAINING_MS

/**
 * Whether the position reached when playback pauses is worth saving: there must be a book, a known
 * length and some listening (a position of zero means nothing was played).
 */
fun shouldSaveProgress(bookId: String?, positionMs: Long, durationMs: Long): Boolean =
    !bookId.isNullOrEmpty() && durationMs > 0L && positionMs > 0L
