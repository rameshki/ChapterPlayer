package com.chapterplayer.domain.settings

import com.chapterplayer.data.sync.ServerSyncPhase

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** The longest failure reason a server row shows. */
private const val MAX_REASON_CHARS = 120

/** "just now", "12 min ago", "3 h ago", "2 days ago" for something [elapsedMs] ago; a negative time is now. */
fun timeAgo(elapsedMs: Long): String = when {
    elapsedMs < MINUTE_MS -> "just now"
    elapsedMs < HOUR_MS -> "${elapsedMs / MINUTE_MS} min ago"
    elapsedMs < DAY_MS -> "${elapsedMs / HOUR_MS} h ago"
    else -> (elapsedMs / DAY_MS).let { days -> if (days == 1L) "1 day ago" else "$days days ago" }
}

/** One line for a server row: "Last synced 12 min ago · 342 books", "Syncing…" or "Sync failed: <reason>". */
fun serverStatusLine(phase: ServerSyncPhase, nowMs: Long): String = when (phase) {
    ServerSyncPhase.Syncing -> "Syncing…"
    is ServerSyncPhase.Failed -> "Sync failed: " + shorten(phase.message)
    is ServerSyncPhase.Idle -> {
        val at = phase.lastSuccessAtMs
        if (at == null) "Not synced yet"
        else "Last synced ${timeAgo(nowMs - at)} · ${phase.bookCount} ${if (phase.bookCount == 1) "book" else "books"}"
    }
}

private fun shorten(text: String): String {
    val oneLine = text.replace('\n', ' ').trim()
    return if (oneLine.length <= MAX_REASON_CHARS) oneLine else oneLine.take(MAX_REASON_CHARS - 1) + "…"
}
