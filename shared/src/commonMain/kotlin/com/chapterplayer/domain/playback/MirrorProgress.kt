package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.sync.ServerStatusRecord
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.DEFAULT_SOURCE_PREFERENCE
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.mergeDuplicates
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Keeping the copies of a merged book in step (merged sources, Phase 2). A book that several servers have is one entry,
 * but each copy keeps its own progress row and its own server progress. Whenever a copy's progress is saved, the other
 * copies' LOCAL rows follow at once (flagged `mirrored`), and the other copies' servers hear of it later through the
 * pending queue. Positions map by fraction of the whole book (chapters differ between copies, so never by chapter).
 * Newest wins: a copy whose own progress is newer is never overwritten, and a mirrored row is never mirrored on.
 */

/** How often, at most, the other copy's server hears the position while a book plays. */
const val MIRROR_PUSH_INTERVAL_MS = 60_000L

/**
 * A copy's own row this close to the mapped position is kept as it is: it is an echo of itself (a mirror pushed to the
 * other server, read back by a sync, mapped back here), not news.
 */
const val MIRROR_ECHO_MS = 15_000L

/**
 * How much newer than both a queued mirror and this phone's row the source copy's server progress must be to count as
 * listening elsewhere (this phone's own syncs keep that server a few seconds ahead of the queue while playing).
 */
const val MIRROR_STALE_SLACK_MS = 60_000L

/** What the flusher does with a queued mirror. */
enum class MirrorFlush { SEND, DROP }

/**
 * Whether a queued mirror [row] (its `mirrorOf` is the source copy) is still worth sending to its copy's server; the
 * flusher's own rule (that server holds something newer) is checked as well. DROP when:
 * - the two are no longer copies of one book ([stillCopies] false: "Not the same book", or the setting is off);
 * - the copy itself was listened to here later ([targetLocal], a row of its own newer than the row);
 * - the source copy is somewhere else now ([sourceLocal], a row of its own newer than the row whose place or finished
 *   state differs: a merge brought listening from another device; just playing on queues a newer mirror anyway);
 * - the source copy's server holds listening this phone does not have ([sourceServer] newer than the row and than this
 *   phone's source row by more than [MIRROR_STALE_SLACK_MS]): delivered late, the mirror would be stamped as the newest.
 */
fun mirrorFlushDecision(
    row: PendingProgress,
    stillCopies: Boolean,
    targetLocal: ReadingProgress?,
    sourceLocal: ReadingProgress?,
    sourceServer: ServerProgress?,
): MirrorFlush {
    if (!stillCopies) return MirrorFlush.DROP
    if (targetLocal != null && !targetLocal.mirrored && targetLocal.lastPlayedAt > row.updatedAtMs) return MirrorFlush.DROP
    if (sourceLocal != null && !sourceLocal.mirrored && sourceLocal.lastPlayedAt > row.updatedAtMs) {
        val rowFinished = row.markedFinished ?: isFinishedAt(row.positionMs, row.durationMs)
        if (sourceLocal.isFinished != rowFinished) return MirrorFlush.DROP
        if (!rowFinished) {
            val mapped = mirrorPosition(sourceLocal.positionMs, sourceLocal.durationMs, row.durationMs) ?: return MirrorFlush.DROP
            if (abs(mapped - row.positionMs) > MIRROR_ECHO_MS) return MirrorFlush.DROP
        }
    }
    if (sourceServer != null && sourceServer.updatedAtMs > row.updatedAtMs + MIRROR_STALE_SLACK_MS &&
        (sourceLocal == null || sourceServer.updatedAtMs > sourceLocal.lastPlayedAt + MIRROR_STALE_SLACK_MS)
    ) return MirrorFlush.DROP
    return MirrorFlush.SEND
}

/**
 * Whether a queued [row] gives way to [server] progress of its copy in the server merge: a mirror older than what the
 * server holds is out of date and is removed, so it never holds back newer listening; the book's own row or the user's
 * mark keeps the merge waiting, as before.
 */
fun mirrorRowYields(row: PendingProgress, server: ServerProgress): Boolean =
    row.mirrorOf != null && server.updatedAtMs > row.updatedAtMs

/** Another copy of a book: its id, its server and its length (0 when unknown). */
data class MirrorPeer(val bookId: String, val serverId: String, val durationMs: Long)

/**
 * [positionMs] in a copy [fromDurationMs] long, mapped onto a copy [toDurationMs] long by fraction of the whole book,
 * rounded to whole seconds and never past the end. Null when either length is unknown (0 or less).
 */
fun mirrorPosition(positionMs: Long, fromDurationMs: Long, toDurationMs: Long): Long? {
    if (fromDurationMs <= 0L || toDurationMs <= 0L) return null
    val mapped = positionMs.coerceAtLeast(0L).toDouble() / fromDurationMs * toDurationMs
    val seconds = (mapped / 1000.0).roundToLong() * 1000L
    return seconds.coerceIn(0L, toDurationMs)
}

/**
 * What the local row of the copy [targetBookId] becomes after [source] (another copy's progress) was saved; null when
 * it must stay as it is. [targetDurationMs] is the copy's length (0 when unknown: its stored row's length is used).
 *
 * - A mirrored [source] is never mirrored on (no storms), and a copy is not its own peer.
 * - The position maps by fraction ([mirrorPosition]); without both lengths nothing is mirrored, except a restart at 0
 *   (maps to 0 anyway) and a finished copy whose length is unknown (the other copy is finished at its end).
 * - Newest wins: a row newer than [source] stays; so does a row of the copy's own listening that is as new, or that is
 *   within [MIRROR_ECHO_MS] of the mapped position with the same finished state (an echo).
 * - The result carries the source's time and finished state and is flagged mirrored; the same row twice is not rewritten.
 */
fun mirroredProgress(source: ReadingProgress, targetBookId: String, targetDurationMs: Long, existing: ReadingProgress?): ReadingProgress? {
    if (source.mirrored || targetBookId == source.bookId) return null
    val targetDuration = targetDurationMs.takeIf { it > 0L } ?: existing?.durationMs?.takeIf { it > 0L } ?: 0L
    val position = when {
        !source.isFinished && source.positionMs <= 0L -> 0L
        source.isFinished && source.durationMs <= 0L -> targetDuration.takeIf { it > 0L } ?: return null
        else -> mirrorPosition(source.positionMs, source.durationMs, targetDuration) ?: return null
    }
    if (existing != null) {
        if (existing.lastPlayedAt > source.lastPlayedAt) return null
        if (!existing.mirrored) {
            if (existing.lastPlayedAt == source.lastPlayedAt) return null
            if (existing.isFinished == source.isFinished && abs(existing.positionMs - position) <= MIRROR_ECHO_MS) return null
        }
    }
    val result = ReadingProgress(targetBookId, position, targetDuration, source.lastPlayedAt, source.isFinished, mirrored = true)
    return result.takeIf { it != existing }
}

/**
 * Book id to the ids of its other copies, for every book that is merged with another (the same grouping as the lists,
 * `mergeDuplicates`, with the user's corrections: a split pair is not grouped, so it is not mirrored). Download state
 * and the source preference only order the members, so they play no part here. The other copies keep the group order.
 */
fun mirrorGroups(books: List<AudioBook>, overrides: MergeOverrides): Map<String, List<String>> {
    val items = books.map { BrowseItem(it, null, DownloadState.NOT_DOWNLOADED) }
    val result = HashMap<String, List<String>>()
    for (entry in mergeDuplicates(items, overrides, DEFAULT_SOURCE_PREFERENCE, emptySet())) {
        if (!entry.isMerged) continue
        val ids = entry.sources.map { it.book.id }.sorted()
        for (id in ids) result[id] = ids.filter { it != id }
    }
    return result
}

/** Whether the other copy's server is due to hear the position: on pause or stop always, while playing once a [intervalMs]. */
fun mirrorPushDue(lastPushedAtMs: Long?, nowMs: Long, isPlaying: Boolean, intervalMs: Long = MIRROR_PUSH_INTERVAL_MS): Boolean {
    if (!isPlaying || lastPushedAtMs == null) return true
    return nowMs < lastPushedAtMs || nowMs - lastPushedAtMs >= intervalMs
}

/**
 * The copies whose servers hear a mirrored position: not the copy loaded in the player (it reports for itself), and
 * not a copy on a server whose last sync failed. A phone that is offline still queues; the flusher delivers later.
 */
fun mirrorPushTargets(peers: List<MirrorPeer>, loadedBookId: String?, unreachableServerIds: Set<String>): List<MirrorPeer> =
    peers.filter { it.bookId != loadedBookId && it.serverId !in unreachableServerIds }

/** The servers whose last error is newer than their last success (a server never synced without error is fine). */
fun unreachableServerIds(records: Map<String, ServerStatusRecord>): Set<String> =
    records.filterTo(LinkedHashMap()) { (_, record) ->
        val errorAt = record.lastErrorAtMs ?: return@filterTo false
        val success = record.lastSuccessAtMs
        success == null || success <= errorAt
    }.keys

/**
 * Another copy's [progress] as it reads on a copy [toDurationMs] long: the position maps by fraction, a finished copy
 * is finished at the end, a restart is 0. Null when it cannot be mapped (a length unknown).
 */
fun progressOnCopy(progress: ServerProgress, toDurationMs: Long): ServerProgress? {
    if (toDurationMs <= 0L) return null
    val position = when {
        progress.isFinished -> mirrorPosition(progress.positionMs, progress.durationMs, toDurationMs) ?: toDurationMs
        progress.positionMs <= 0L -> 0L
        else -> mirrorPosition(progress.positionMs, progress.durationMs, toDurationMs) ?: return null
    }
    return ServerProgress(position, toDurationMs, progress.isFinished, progress.updatedAtMs)
}

/**
 * The newest of this copy's server progress ([own]) and the other copies' progress (already mapped, [progressOnCopy]),
 * to be weighed against this phone's row by [chooseStartPosition]. Another copy wins only when strictly newer; one
 * without a time cannot be weighed and is left out.
 */
fun newestAcrossCopies(own: ServerProgress?, others: List<ServerProgress>): ServerProgress? {
    var best = own
    for (other in others) {
        if (other.updatedAtMs <= 0L) continue
        if (best == null || other.updatedAtMs > best.updatedAtMs) best = other
    }
    return best
}

/**
 * What a mark on every copy reports ([outcomes] per copy, null for a copy that could not be marked): partial when a copy
 * failed, queued when any copy waits for its server, else synced when any server took it.
 */
fun combinedMarkOutcome(outcomes: List<MarkOutcome?>): MarkOutcome = when {
    null in outcomes || MarkOutcome.PARTIAL in outcomes -> MarkOutcome.PARTIAL
    MarkOutcome.QUEUED in outcomes -> MarkOutcome.QUEUED
    MarkOutcome.SYNCED in outcomes -> MarkOutcome.SYNCED
    else -> MarkOutcome.LOCAL_ONLY
}
