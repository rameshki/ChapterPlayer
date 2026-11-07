package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.entryContaining
import com.chapterplayer.domain.browse.resumeCopy

/*
 * "Up next": an ordered list of books, kept on this phone (table `up_next`, Room v15). A row names one book id; a book
 * several servers have is one entry of the lists, so a row stands for the whole entry (found through any copy's id)
 * and the entry is queued at most once. The pure rules live here; `UpNextRepository` stores the list and
 * `UpNextAdvancer` starts the first entry when a book ends.
 */

/** One queued entry: the stored row's id and the list entry it stands for now. */
data class UpNextEntry(val queueId: String, val entry: BrowseItem)

/**
 * The queue against the books there are now: [entries] in queue order, and the [stale] rows (a book that is gone, or
 * a second row for an entry already queued, as after two books were merged). Stale rows are dropped when the queue
 * advances.
 */
data class ResolvedUpNext(val entries: List<UpNextEntry>, val stale: List<String>)

/** Resolves [queue] (stored ids, in order) against [items] (the merged list); see [ResolvedUpNext]. */
fun resolveUpNext(queue: List<String>, items: List<BrowseItem>): ResolvedUpNext {
    val entries = mutableListOf<UpNextEntry>()
    val stale = mutableListOf<String>()
    val seen = HashSet<String>()
    for (id in queue) {
        val entry = entryContaining(items, id)
        if (entry == null || !seen.add(entry.book.id)) stale += id else entries += UpNextEntry(id, entry)
    }
    return ResolvedUpNext(entries, stale)
}

/** What a "Play next" or "Add to Up next" did. */
enum class UpNextOutcome {
    /** Put at the front (Play next). */
    PLAYS_NEXT,
    /** Already queued, now moved to the front (Play next). */
    MOVED_TO_FRONT,
    /** Put at the end (Add to Up next). */
    ADDED,
    /** Add to Up next of a book already queued: it keeps its place. */
    ALREADY_QUEUED,
    /** The book is the one in the player: it is never queued. */
    PLAYING_NOW,
}

/** A changed queue and what the change was. */
data class UpNextEdit(val queue: List<String>, val outcome: UpNextOutcome)

/**
 * "Play next" ([front]) or "Add to Up next" of the entry whose copies are [copyIds] (the first is the row that is
 * stored). A row of any copy counts as the entry being queued. The book in the player ([loadedBookId], any copy) is
 * never queued; the queue is then unchanged.
 */
fun editUpNext(queue: List<String>, copyIds: List<String>, loadedBookId: String?, front: Boolean): UpNextEdit {
    val ids = copyIds.distinct()
    if (ids.isEmpty()) return UpNextEdit(queue, UpNextOutcome.ALREADY_QUEUED)
    if (loadedBookId != null && loadedBookId in ids) return UpNextEdit(queue, UpNextOutcome.PLAYING_NOW)
    val queued = queue.any { it in ids }
    return when {
        front -> UpNextEdit(listOf(ids.first()) + queue.filterNot { it in ids }, if (queued) UpNextOutcome.MOVED_TO_FRONT else UpNextOutcome.PLAYS_NEXT)
        queued -> UpNextEdit(queue, UpNextOutcome.ALREADY_QUEUED)
        else -> UpNextEdit(queue + ids.first(), UpNextOutcome.ADDED)
    }
}

/** [queue] without every row of the entry whose copies are [copyIds]. */
fun removeFromUpNext(queue: List<String>, copyIds: Collection<String>): List<String> = queue.filterNot { it in copyIds }

/**
 * [queue] with the row [queueId] moved [by] places (negative: towards the front), stopping at either end; an unknown
 * row leaves it unchanged.
 */
fun moveInUpNext(queue: List<String>, queueId: String, by: Int): List<String> {
    val from = queue.indexOf(queueId)
    if (from < 0) return queue
    val to = (from + by).coerceIn(0, queue.lastIndex)
    if (to == from) return queue
    val list = queue.toMutableList()
    list.removeAt(from)
    list.add(to, queueId)
    return list
}

/** The snackbar text of an [UpNextOutcome]. */
fun upNextOutcomeText(outcome: UpNextOutcome): String = when (outcome) {
    UpNextOutcome.PLAYS_NEXT -> "Plays next"
    UpNextOutcome.MOVED_TO_FRONT -> "Moved to the front of Up next"
    UpNextOutcome.ADDED -> "Added to Up next"
    UpNextOutcome.ALREADY_QUEUED -> "Already in Up next"
    UpNextOutcome.PLAYING_NOW -> "This book is playing now"
}

const val UP_NEXT_REMOVED_TEXT = "Removed from Up next"
const val UP_NEXT_ERROR_TEXT = "Could not change Up next. Try again."

/**
 * Whether [member] (one copy) can play now without asking anybody: a downloaded copy always; otherwise only online,
 * without "Downloaded only", and (when [reachableServerIds] is known) when its server answers.
 */
fun upNextCanPlay(member: BrowseItem, online: Boolean, downloadedOnly: Boolean, reachableServerIds: Set<String>?): Boolean {
    if (member.downloadState == DownloadState.DOWNLOADED) return true
    if (!online || downloadedOnly) return false
    return reachableServerIds == null || member.book.serverId in reachableServerIds
}

/** The entry that starts: its row and the copy to load. */
data class UpNextPick(val queueId: String, val copy: BrowseItem)

/**
 * What happens when a book ends: [start] the first queued entry that can play (null: none can), [drop] the rows to
 * remove now (stale rows and any row of the book that just ended), and the entries [skipped] because no copy can play
 * now (offline and not downloaded, say); they stay queued.
 */
data class UpNextPlan(val start: UpNextPick?, val drop: List<String>, val skipped: List<UpNextEntry>)

/**
 * The plan for the end of [endedBookId]. The copy is chosen without asking (nobody may be looking at the phone): the
 * rule of the car and the notification ([resumeCopy]: a downloaded copy first, else the copy played last), among the
 * copies [canPlay] allows.
 */
fun planUpNext(queue: List<String>, items: List<BrowseItem>, endedBookId: String?, canPlay: (BrowseItem) -> Boolean): UpNextPlan {
    val resolved = resolveUpNext(queue, items)
    val drop = resolved.stale.toMutableList()
    val skipped = mutableListOf<UpNextEntry>()
    for (row in resolved.entries) {
        if (endedBookId != null && row.entry.allSources.any { it.book.id == endedBookId }) {
            drop += row.queueId
            continue
        }
        if (row.entry.allSources.none(canPlay)) {
            skipped += row
            continue
        }
        return UpNextPlan(UpNextPick(row.queueId, resumeCopy(row.entry, canPlay)), drop, skipped)
    }
    return UpNextPlan(null, drop, skipped)
}

/** Why a queued book that is not on the phone cannot play now, in words for the note. */
fun upNextSkipReason(online: Boolean, downloadedOnly: Boolean): String = when {
    !online -> "needs a connection"
    downloadedOnly -> "Downloaded only is on"
    else -> "its server does not answer"
}

/**
 * The note when the end of a book skipped queued books that cannot play now ([skippedTitles], in queue order, all for
 * [reason], see [upNextSkipReason]); [started] says whether a later one started. Null when nothing was skipped.
 */
fun upNextSkipText(skippedTitles: List<String>, started: Boolean, reason: String): String? {
    if (skippedTitles.isEmpty()) return null
    val what = if (skippedTitles.size == 1) "\"${skippedTitles.first()}\"" else "${skippedTitles.size} books"
    return if (started) "Up next skipped $what: $reason" else "Up next: $what cannot play now ($reason)"
}

/** The note when the next book of Up next did not start; [reason] is the starter's safe message. */
fun upNextStartFailedText(reason: String): String = "Up next could not start the next book. $reason"

/** The rows of [queue] that stand for the entry holding [bookId] (any copy); empty when it is not queued. */
fun upNextRowsOf(queue: List<String>, items: List<BrowseItem>, bookId: String): List<String> {
    val entry = entryContaining(items, bookId)
    val copies = entry?.allSources?.mapTo(HashSet()) { it.book.id } ?: setOf(bookId)
    return queue.filter { it in copies || it == bookId }
}

/** How close to the end the sleep timer may run out and still count as what stopped the book. */
const val UP_NEXT_SLEEP_MARGIN_MS = 3_000L

/**
 * Whether the end in [now] is a book that played to its end, so Up next moves on. [lastBeforeEnd] is the last state
 * of the same book before the end was reached. Only an end reached while playing counts: a pause (or stop) just before
 * the end, a seek to the end while paused, another book loaded, an error, and a sleep timer that was due to run out
 * at the end (it stopped the book, so the next one does not start) do not.
 */
fun endedWhilePlaying(lastBeforeEnd: PlaybackState?, now: PlaybackState): Boolean {
    if (!now.ended || now.error != null || now.bookId == null) return false
    val before = lastBeforeEnd ?: return false
    if (before.bookId != now.bookId || before.ended) return false
    // Playing was asked for until the end: a paused book never gets here by itself.
    if (!before.playWhenReady) return false
    val speed = if (before.speed > 0f) before.speed else 1f
    val bookLeft = (before.durationMs - before.positionMs).coerceAtLeast(0L)
    // Playing reports its place twice a second: an end further away than that was jumped to (a 30 s skip), not played to.
    if (bookLeft > (UP_NEXT_END_REACH_MS * maxOf(speed, 1f)).toLong()) return false
    val sleep = before.sleepRemainingMs ?: return true
    return sleep > (bookLeft / speed).toLong() + UP_NEXT_SLEEP_MARGIN_MS
}

/** How much book may be left in the last state before the end for the end to count as played to (not jumped to). */
const val UP_NEXT_END_REACH_MS = 5_000L

/**
 * The sleep timer the next book starts with, so it keeps counting down through an advance: an "After" timer with the
 * wall time it had left when the book ended ([remainingAtEndMs]) less what the advance took ([elapsedMs]), at least
 * one second; null when no timer was set (or it was "End of chapter", which never lets the book end and move on).
 */
fun carriedSleepTimer(timer: SleepTimer, remainingAtEndMs: Long?, elapsedMs: Long): SleepTimer? {
    if (timer !is SleepTimer.After || remainingAtEndMs == null) return null
    val left = (remainingAtEndMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(MIN_CARRIED_SLEEP_MS)
    return SleepTimer.After(minutes = ((left + 59_999L) / 60_000L).toInt(), durationMs = left)
}

/** The wall time a sleep timer had left at the end of the book, from the last state before the end ([before]). */
fun sleepLeftAtEnd(before: PlaybackState): Long? {
    val sleep = before.sleepRemainingMs ?: return null
    val speed = if (before.speed > 0f) before.speed else 1f
    val bookLeftWall = ((before.durationMs - before.positionMs).coerceAtLeast(0L) / speed).toLong()
    return (sleep - bookLeftWall).coerceAtLeast(0L)
}

private const val MIN_CARRIED_SLEEP_MS = 1_000L
