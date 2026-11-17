package com.chapterplayer.domain.diagnostics

/*
 * What the playback journal and the "why Android ended the app" lines look like (review R36). Pure text rules, so they
 * are host-tested. Times are written 03h49m12s, never 03:49:12: redact() takes a colon-separated time for an IPv6
 * address and would blank it. Nothing here names a book, a server or a file.
 */

/** The journal keeps this many lines on the phone. */
const val JOURNAL_LINES = 200

/** A book position or a clock time as 1h02m03s (hours are left out below one hour: 4m05s). */
fun journalDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    val ss = s.toString().padStart(2, '0')
    return if (h > 0L) "${h}h${m.toString().padStart(2, '0')}m${ss}s" else "${m}m${ss}s"
}

/** One journal event: what happened and, when known, where in the book. */
fun journalEvent(event: String, positionMs: Long?): String =
    if (positionMs == null) event else "$event at ${journalDuration(positionMs)}"

/** [lines] cut to the newest [max]. */
fun trimJournal(lines: List<String>, max: Int = JOURNAL_LINES): List<String> = lines.takeLast(max)

private val PROCESS_EXIT_REASONS = mapOf(
    0 to "unknown", 1 to "app closed itself", 2 to "killed by a signal", 3 to "killed for low memory", 4 to "crash", 5 to "native crash",
    6 to "not responding (ANR)", 7 to "failed to start", 8 to "permission changed", 9 to "too many resources used",
    10 to "closed by the user", 11 to "stopped by the user", 12 to "a service it needed died", 13 to "other",
    14 to "frozen by the system", 15 to "package state changed", 16 to "app updated",
)

private val PROCESS_IMPORTANCES = mapOf(
    100 to "was in front", 125 to "was a foreground service (playing)", 200 to "was visible", 230 to "was perceptible",
    300 to "was a background service", 400 to "was cached", 1000 to "was gone",
)

/**
 * One line for one of Android's own records of why the app's process ended (ApplicationExitInfo): [timeText] is already
 * formatted, [reason] and [importance] are the system's numbers, [status] the exit status or signal.
 */
fun describeProcessExit(timeText: String, reason: Int, status: Int, importance: Int): String {
    val why = PROCESS_EXIT_REASONS[reason] ?: "reason $reason"
    val state = PROCESS_IMPORTANCES[importance] ?: "importance $importance"
    return "$timeText $why (status $status), $state"
}
