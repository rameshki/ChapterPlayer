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

/**
 * Where a book starts. A [requestedMs] of zero or more is taken as it is; a negative one means "where the
 * server says" ([serverPositionMs]), except that a book the server counts as finished starts from the
 * beginning.
 */
fun resolveStartMs(requestedMs: Long, serverPositionMs: Long, durationMs: Long): Long = when {
    requestedMs >= 0L -> requestedMs
    isFinishedAt(serverPositionMs, durationMs) -> 0L
    else -> serverPositionMs.coerceAtLeast(0L)
}

/**
 * Whether a media item may be queued in the exported service: only from this app itself, and only a track
 * of ours, whose id looks like `<bookId>/<trackIndex>`.
 */
fun isOwnTrackRequest(callerPackage: String?, ownPackage: String, mediaId: String): Boolean {
    if (callerPackage == null || callerPackage != ownPackage) return false
    val slash = mediaId.lastIndexOf('/')
    if (slash <= 0 || slash == mediaId.length - 1) return false
    return mediaId.substring(slash + 1).all { it in '0'..'9' }
}
