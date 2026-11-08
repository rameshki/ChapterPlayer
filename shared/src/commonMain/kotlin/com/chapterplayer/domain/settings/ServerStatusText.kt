package com.chapterplayer.domain.settings

import com.chapterplayer.data.sync.ServerSyncPhase
import com.chapterplayer.domain.jellyfin.JELLYFIN_NOT_JELLYFIN_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_TOO_OLD_TEXT
import com.chapterplayer.domain.jellyfin.JellyfinServerCheck

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

/** The kind label of a Jellyfin server's one connection in the "Test connection" dialog. */
const val JELLYFIN_CONNECTION_KIND = "Jellyfin address"

/**
 * "Test connection" for a Jellyfin server (its public info, no token): whether it passed and the words, such as
 * "Jellyfin 12.4 answered".
 */
fun jellyfinTestResult(check: JellyfinServerCheck): Pair<Boolean, String> = when (check) {
    is JellyfinServerCheck.Ok -> true to "Jellyfin ${shortVersion(check.version)} answered"
    JellyfinServerCheck.NotJellyfin -> false to JELLYFIN_NOT_JELLYFIN_TEXT
    is JellyfinServerCheck.TooOld -> false to JELLYFIN_TOO_OLD_TEXT
}

/** "The server answered with HTTP 502": a Jellyfin test that got an error status. */
fun jellyfinHttpStatusText(code: Int): String = "The server answered with HTTP $code"

/** "12.4.0" -> "12.4"; anything without two numbered parts stays as it is. */
private fun shortVersion(version: String): String {
    val parts = version.split('.')
    return if (parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) "${parts[0]}.${parts[1]}" else version
}
