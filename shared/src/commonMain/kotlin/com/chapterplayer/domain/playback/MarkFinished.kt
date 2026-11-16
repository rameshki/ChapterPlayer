package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress

/** What the player menu offers for a book's finished state. */
enum class MarkAction { MarkFinished, MarkNotFinished }

/**
 * What happened to a mark: the server took it, it waits in the queue for the server, or the book's server keeps no progress.
 * The local state changed in all three. PARTIAL: a book on several servers was marked, but one of its copies could not be;
 * PARTIAL_QUEUED: the same, and a copy's mark waits in the queue for its server.
 */
enum class MarkOutcome { SYNCED, QUEUED, LOCAL_ONLY, PARTIAL, PARTIAL_QUEUED }

/** What the player's save of a book pinned by the user's finished mark ([keepsFinishedMark]) does, see [pinnedSave]. */
enum class PinnedSave {
    /** The place from before the rewind: stored as the mark's 0, still finished. */
    OLD_PLACE,
    /** At the start (the pause after the rewind): stored as it is, still finished. */
    KEEP,
    /** Listening again: the pin ends and the book is no longer finished (the owner's rule). */
    LISTENING_AGAIN,
}

/**
 * The player's save at [positionMs] made at [nowMs] of a book the user marked finished while it was loaded ([rewoundAtMs]:
 * the mark that rewound it, null once the rewind was seen). The old place is the mark's 0; a place at the start keeps the
 * mark; anything past the start is listening again, which ends the finished state. When the rewind failed
 * ([rewindFailed]: the player may still stand at the old place) nothing past the start ends the mark: it is the old place
 * until a save at the start shows the player there.
 */
fun pinnedSave(positionMs: Long, nowMs: Long, rewoundAtMs: Long?, rewindFailed: Boolean = false): PinnedSave = when {
    rewindFailed && positionMs > MARK_REWIND_SEEN_MS -> PinnedSave.OLD_PLACE
    isSaveBeforeRewind(positionMs, nowMs, rewoundAtMs) -> PinnedSave.OLD_PLACE
    positionMs > MARK_REWIND_SEEN_MS -> PinnedSave.LISTENING_AGAIN
    else -> PinnedSave.KEEP
}

/**
 * Whether a save from the player turns a finished book into one that is not finished: playing a finished book again
 * (after Mark as finished, Play again, or after hearing it to the end) makes it not finished, here and on its server.
 * Only real listening counts: a save at the very start (Play then Pause within [MARK_REWIND_SEEN_MS]) changes nothing.
 */
fun playedAgain(existing: ReadingProgress?, saved: ReadingProgress): Boolean =
    existing != null && existing.isFinished && !saved.isFinished && saved.positionMs > MARK_REWIND_SEEN_MS

/** Whether the player's save of a finished book at the very start keeps it finished (no real listening yet, see [playedAgain]). */
fun staysFinishedAtStart(existing: ReadingProgress?, save: ReadingProgress): Boolean =
    existing != null && existing.isFinished && !save.isFinished && save.positionMs <= MARK_REWIND_SEEN_MS

/**
 * Whether a live report of a book whose finished mark silenced its session (made at [markedAtMs], see
 * [reportAfterMark]) may go to the server.
 */
enum class MarkedReport {
    /** Held back; the book stays silenced. */
    SKIP,
    /** Sent, and the book reports normally from now on (the person listens again). */
    SEND_AND_RESUME,
}

/**
 * What happens to a live report at [positionMs] made at [nowMs] ([isPlaying] false for the report on pause) of the loaded
 * book after the user marked it finished at [markedAtMs]. Audiobookshelf un-finishes a finished book when a report moves
 * its time away from the end, so the old place must never reach it:
 * - the old place (more than [MARK_REWIND_SEEN_MS] from the start within [MARK_REWIND_GRACE_MS] of the mark, the player's
 *   place from before the rewind, paused or a tick that came first) is skipped;
 * - the pause at the start after the rewind is skipped (nothing was heard);
 * - anything else (playing again, or a later report past the start) is listening again: sent, and the silence ends, so
 *   that listening reaches the server (where it may genuinely un-finish the book).
 */
fun reportAfterMark(positionMs: Long, nowMs: Long, markedAtMs: Long, isPlaying: Boolean, rewindFailed: Boolean = false): MarkedReport = when {
    rewindFailed && positionMs > MARK_REWIND_SEEN_MS -> MarkedReport.SKIP
    isSaveBeforeRewind(positionMs, nowMs, markedAtMs) -> MarkedReport.SKIP
    !isPlaying && positionMs <= MARK_REWIND_SEEN_MS -> MarkedReport.SKIP
    else -> MarkedReport.SEND_AND_RESUME
}

/** "Mark as not finished" for a book that counts as finished, "Mark as finished" for everything else (no progress included). */
fun markActionFor(progress: ReadingProgress?): MarkAction =
    if (progress != null && progress.isFinished) MarkAction.MarkNotFinished else MarkAction.MarkFinished

/**
 * What the local progress of a book becomes after the user marks it. Both start over at 0: finished sets the flag and
 * rewinds the book to the start (the owner's rule: a finished book is ready to be heard again from 00:00), not finished
 * resets it too (the server resets the book as well). Both carry [nowMs], so the newest-wins rules of the sync
 * ([flushDecision], [mergeServerProgress], [coalescePending]) see the mark as the latest word.
 * [durationMs] is the book's length, used when there is no progress row yet.
 */
fun progressAfterMark(current: ReadingProgress?, bookId: String, finished: Boolean, durationMs: Long, nowMs: Long): ReadingProgress {
    val duration = current?.durationMs?.takeIf { it > 0L } ?: durationMs
    return ReadingProgress(bookId, 0L, duration, nowMs, isFinished = finished)
}

/**
 * What the phone keeps after the server took a mark and answered with [answer]. A finished answer to a finished mark keeps
 * the mark's place (0): servers keep a finished book's old place (Audiobookshelf) or its end (Plex), and that must not
 * bring the old time back. Anything else is the server's word (an un-finished book is reset to 0 there). The time stays
 * the mark's, so newer listening still wins.
 */
fun progressAfterServerAnswer(mark: ReadingProgress, answer: ServerProgress): ReadingProgress {
    val duration = answer.durationMs.takeIf { it > 0L } ?: mark.durationMs
    val position = if (mark.isFinished && answer.isFinished) mark.positionMs else answer.positionMs
    return ReadingProgress(mark.bookId, position, duration, mark.lastPlayedAt, answer.isFinished)
}

/** How long after a finished mark that rewound the loaded book a save away from the start is read as the place before the rewind. */
const val MARK_REWIND_GRACE_MS = 5_000L

/** A save this close to the start counts as the player after the rewind. */
const val MARK_REWIND_SEEN_MS = 2_000L

/**
 * Whether a save of the loaded book at [positionMs] made at [nowMs] still carries the place from before the rewind that a
 * finished mark at [rewoundAtMs] asked for (the player pauses and seeks asynchronously, so the saver can still report
 * the old place once). Such a save is stored at 0 instead. A save near the start, or one after [MARK_REWIND_GRACE_MS],
 * is the player's real place (the person may be listening again).
 */
fun isSaveBeforeRewind(positionMs: Long, nowMs: Long, rewoundAtMs: Long?): Boolean =
    rewoundAtMs != null && positionMs > MARK_REWIND_SEEN_MS && nowMs - rewoundAtMs in 0L..MARK_REWIND_GRACE_MS

/**
 * Whether the periodic save of the loaded book must keep the finished flag. Only a mark the user made in this session counts:
 * [userMarkAt] is the time of that mark (or of the last save that kept it), [loadedSinceMs] is when the book became the loaded
 * one. The flag is kept only while the stored row is still finished with exactly that time, so "Mark as not finished" (or
 * anything else that rewrote the row) clears it, and a finished row merged from the server never pins it. Evaluated on every save.
 */
fun keepsFinishedMark(existing: ReadingProgress?, userMarkAt: Long?, loadedSinceMs: Long): Boolean =
    existing != null && existing.isFinished && userMarkAt != null && existing.lastPlayedAt == userMarkAt && userMarkAt >= loadedSinceMs

/** The snackbar text after a mark (the player's menu and the book long-press menu say the same). */
fun markResultText(finished: Boolean, outcome: MarkOutcome): String {
    val done = if (finished) "Marked as finished" else "Marked as not finished"
    return when (outcome) {
        MarkOutcome.QUEUED -> "$done. It will reach the server when it is back."
        MarkOutcome.PARTIAL -> "$done. Another copy of this book could not be changed."
        MarkOutcome.PARTIAL_QUEUED -> "$done. Another copy could not be changed; the rest reaches the servers when they are back."
        MarkOutcome.SYNCED, MarkOutcome.LOCAL_ONLY -> done
    }
}

/** When a mark could not be made at all. */
const val MARK_ERROR_TEXT = "Could not update the book. Try again."
