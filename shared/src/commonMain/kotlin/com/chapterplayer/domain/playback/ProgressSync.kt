package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress

/**
 * Represents progress state retrieved from a remote server.
 */
data class ServerProgress(val positionMs: Long, val durationMs: Long, val isFinished: Boolean, val updatedAtMs: Long)

/**
 * Represents progress that has been recorded locally but not yet successfully synchronized with the server.
 */
data class PendingProgress(val bookId: String, val serverId: String, val positionMs: Long, val durationMs: Long, val listenedMs: Long, val updatedAtMs: Long, val attempts: Int = 0,
    /** The earliest time to try again; 0 when it may be sent at once. Kept by the queue, not by [coalescePending]. */
    val nextAttemptAtMs: Long = 0L,
    /** Set by "Mark as finished / not finished": the finished state to deliver as it is, instead of deriving it from the position. Null for ordinary positions. */
    val markedFinished: Boolean? = null,
    /**
     * Set on a mirror (merged sources): the id of the copy whose listening this row repeats on [bookId]'s server, queued by
     * `ProgressMirrorPusher`. Null for the book's own positions and for marks. See `mirrorFlushDecision`.
     */
    val mirrorOf: String? = null)

/**
 * Dictates whether a pending progress update should be sent to the server or discarded.
 */
enum class FlushDecision { SEND, DROP_SERVER_NEWER }

/**
 * Determines the best position to start playback from by comparing local and server progress.
 */
fun chooseStartPosition(local: ReadingProgress?, server: ServerProgress?): Long? {
    // The server wins only when it is strictly newer (and has a real timestamp); ties go to the phone.
    val (pos, dur, finished) = when {
        server != null && (local == null || (server.updatedAtMs > 0 && server.updatedAtMs > local.lastPlayedAt)) ->
            Triple(server.positionMs, server.durationMs, server.isFinished)
        local != null -> Triple(local.positionMs, local.durationMs, local.isFinished)
        else -> return null
    }
    return if (finished || isFinishedAt(pos, dur)) 0L else pos.coerceAtLeast(0L)
}

/** How long a queued position is kept before it is given up: a server that is gone for good must not retry forever. */
const val PENDING_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

/** True when a position queued at [updatedAtMs] is older than [maxAgeMs] at [nowMs]; a time in the future is never expired. */
fun isPendingExpired(updatedAtMs: Long, nowMs: Long, maxAgeMs: Long = PENDING_MAX_AGE_MS): Boolean =
    nowMs - updatedAtMs > maxAgeMs

/**
 * Determines if a local progress save operation should be triggered based on time and playback state.
 */
fun shouldSaveNow(lastSavedAtMs: Long, nowMs: Long, isPlaying: Boolean, intervalMs: Long = 10_000): Boolean {
    if (!isPlaying) return false
    return nowMs < lastSavedAtMs || (nowMs - lastSavedAtMs >= intervalMs)
}

/**
 * Determines if a synchronization with the server should be triggered based on time and playback state.
 */
fun shouldSyncNow(lastSyncedAtMs: Long, nowMs: Long, isPlaying: Boolean, intervalMs: Long = 10_000): Boolean {
    if (!isPlaying) return false
    return nowMs < lastSyncedAtMs || (nowMs - lastSyncedAtMs >= intervalMs)
}

/**
 * Calculates the next exponential backoff delay in milliseconds for retry attempts.
 */
fun nextBackoffMs(attempts: Int): Long {
    return when {
        attempts <= 0 -> 0L
        attempts == 1 -> 5_000L
        attempts == 2 -> 10_000L
        attempts == 3 -> 20_000L
        attempts == 4 -> 40_000L
        else -> 300_000L
    }
}

/**
 * Merges a new progress update into an existing pending queue, aggregating listened time.
 */
fun coalescePending(existing: PendingProgress?, incoming: PendingProgress): PendingProgress {
    if (existing == null) return incoming
    val totalListened = existing.listenedMs + incoming.listenedMs
    val newer = incoming.updatedAtMs >= existing.updatedAtMs
    // An explicit mark is a user action that must reach the server: a later ordinary position never removes it; of two marks the newer wins.
    // A finished state that only repeats another copy (a mirror, [PendingProgress.mirrorOf]) is no user action: newer rows replace it.
    val existingUserMark = existing.markedFinished != null && existing.mirrorOf == null
    val incomingUserMark = incoming.markedFinished != null && incoming.mirrorOf == null
    val mark = when {
        existing.markedFinished == null -> incoming.markedFinished
        incoming.markedFinished == null -> if (newer && !existingUserMark) null else existing.markedFinished
        newer -> incoming.markedFinished
        else -> existing.markedFinished
    }
    // A row that carries the user's own mark is never a mirror (queued mirrors may be dropped).
    val userMark = existingUserMark || incomingUserMark
    return if (newer) {
        incoming.copy(listenedMs = totalListened, attempts = 0, markedFinished = mark, mirrorOf = if (userMark) null else incoming.mirrorOf)
    } else {
        // Keep existing metadata but update the cumulative listened time
        existing.copy(listenedMs = totalListened, markedFinished = mark, mirrorOf = if (userMark) null else existing.mirrorOf)
    }
}

/**
 * Decides whether to push pending progress to the server or drop it based on server freshness.
 */
fun flushDecision(pending: PendingProgress, server: ServerProgress?): FlushDecision {
    return if (server != null && server.updatedAtMs > pending.updatedAtMs) {
        FlushDecision.DROP_SERVER_NEWER
    } else {
        FlushDecision.SEND
    }
}

/**
 * Merges server progress into the local state, provided there are no newer pending local updates.
 * A server time later than [nowMs] (a server clock ahead) counts as [nowMs], so it cannot block every later save.
 * A mirrored local row (merged sources) is kept when the server only echoes it: the same finished state and a position
 * within [MIRROR_ECHO_MS] (the app pushed it there), so the lists keep showing the copy really played.
 */
fun mergeServerProgress(bookId: String, local: ReadingProgress?, server: ServerProgress, hasPendingLocal: Boolean, nowMs: Long = Long.MAX_VALUE): ReadingProgress? {
    if (hasPendingLocal) return null
    val stamp = minOf(server.updatedAtMs, nowMs)
    if (local != null && local.lastPlayedAt >= stamp) return null
    if (local != null && local.mirrored && local.isFinished == server.isFinished &&
        kotlin.math.abs(local.positionMs - server.positionMs) <= MIRROR_ECHO_MS
    ) return null

    return ReadingProgress(
        bookId = bookId,
        positionMs = server.positionMs,
        durationMs = server.durationMs,
        lastPlayedAt = stamp,
        isFinished = server.isFinished
    )
}
