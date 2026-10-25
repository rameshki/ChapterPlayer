package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress

/** What the player menu offers for a book's finished state. */
enum class MarkAction { MarkFinished, MarkNotFinished }

/** "Mark as not finished" for a book that counts as finished, "Mark as finished" for everything else (no progress included). */
fun markActionFor(progress: ReadingProgress?): MarkAction =
    if (progress != null && progress.isFinished) MarkAction.MarkNotFinished else MarkAction.MarkFinished

/**
 * What the local progress of a book becomes after the user marks it. Finished keeps the position (like the server does), not
 * finished starts over at 0 (the server resets the book too). Both carry [nowMs], so the newest-wins rules of the sync
 * ([flushDecision], [mergeServerProgress], [coalescePending]) see the mark as the latest word.
 * [durationMs] is the book's length, used when there is no progress row yet.
 */
fun progressAfterMark(current: ReadingProgress?, bookId: String, finished: Boolean, durationMs: Long, nowMs: Long): ReadingProgress {
    val duration = current?.durationMs?.takeIf { it > 0L } ?: durationMs
    return if (finished) {
        ReadingProgress(bookId, current?.positionMs ?: 0L, duration, nowMs, isFinished = true)
    } else {
        ReadingProgress(bookId, 0L, duration, nowMs, isFinished = false)
    }
}

/**
 * Whether the periodic save of the loaded book must keep a finished flag it did not set itself. The user can mark the
 * loaded book as finished while it plays; the next save would otherwise write `isFinished = false` over it (the position
 * is not near the end). The mark is recognised by being newer than [trackedSinceMs] (when this book became the loaded
 * one) and not written by the saver ([lastOwnWriteMs]); once seen it is kept ([alreadyKept]) until another book loads.
 */
fun keepsFinishedMark(existing: ReadingProgress?, trackedSinceMs: Long, lastOwnWriteMs: Long?, alreadyKept: Boolean): Boolean =
    alreadyKept || (existing != null && existing.isFinished && existing.lastPlayedAt >= trackedSinceMs && existing.lastPlayedAt != lastOwnWriteMs)
