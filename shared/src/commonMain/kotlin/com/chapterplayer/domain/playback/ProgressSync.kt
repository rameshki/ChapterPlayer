package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress

/**
 * Represents progress state retrieved from a remote server.
 */
data class ServerProgress(val positionMs: Long, val durationMs: Long, val isFinished: Boolean, val updatedAtMs: Long)

/**
 * Represents progress that has been recorded locally but not yet successfully synchronized with the server.
 */
data class PendingProgress(val bookId: String, val serverId: String, val positionMs: Long, val durationMs: Long, val listenedMs: Long, val updatedAtMs: Long, val attempts: Int = 0)

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
    return if (incoming.updatedAtMs >= existing.updatedAtMs) {
        incoming.copy(listenedMs = totalListened, attempts = 0)
    } else {
        // Keep existing metadata but update the cumulative listened time
        existing.copy(listenedMs = totalListened)
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
 */
fun mergeServerProgress(bookId: String, local: ReadingProgress?, server: ServerProgress, hasPendingLocal: Boolean): ReadingProgress? {
    if (hasPendingLocal) return null
    if (local != null && local.lastPlayedAt >= server.updatedAtMs) return null
    
    return ReadingProgress(
        bookId = bookId,
        positionMs = server.positionMs,
        durationMs = server.durationMs,
        lastPlayedAt = server.updatedAtMs,
        isFinished = server.isFinished
    )
}
