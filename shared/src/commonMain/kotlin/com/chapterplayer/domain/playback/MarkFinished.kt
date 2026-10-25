package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress

/** What the player menu offers for a book's finished state. */
enum class MarkAction { MarkFinished, MarkNotFinished }

/** What happened to a mark: the server took it, it waits in the queue for the server, or the book's server keeps no progress. The local state changed in all three. */
enum class MarkOutcome { SYNCED, QUEUED, LOCAL_ONLY }

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
 * Whether the periodic save of the loaded book must keep the finished flag. Only a mark the user made in this session counts:
 * [userMarkAt] is the time of that mark (or of the last save that kept it), [loadedSinceMs] is when the book became the loaded
 * one. The flag is kept only while the stored row is still finished with exactly that time, so "Mark as not finished" (or
 * anything else that rewrote the row) clears it, and a finished row merged from the server never pins it. Evaluated on every save.
 */
fun keepsFinishedMark(existing: ReadingProgress?, userMarkAt: Long?, loadedSinceMs: Long): Boolean =
    existing != null && existing.isFinished && userMarkAt != null && existing.lastPlayedAt == userMarkAt && userMarkAt >= loadedSinceMs
