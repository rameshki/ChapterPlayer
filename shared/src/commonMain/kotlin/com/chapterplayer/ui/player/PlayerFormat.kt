package com.chapterplayer.ui.player

import com.chapterplayer.domain.browse.formatClock
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.HistoryEntry
import com.chapterplayer.domain.playback.historyAgoLabel
import kotlin.math.abs

/** The elapsed and remaining texts under a slider. */
data class SliderLabels(val elapsed: String, val remaining: String)

/** Labels for a slider dragged to [fraction] of [totalMs]; they follow the thumb, not the playback. */
fun dragLabels(fraction: Float, totalMs: Long): SliderLabels {
    val total = totalMs.coerceAtLeast(0L)
    val safe = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val elapsed = (safe * total).toLong().coerceIn(0L, total)
    return SliderLabels(formatClock(elapsed), formatClock(total - elapsed))
}

/** A slider is usable only when the length is known. */
fun slidersEnabled(totalMs: Long): Boolean = totalMs > 0L

/** True once playback has caught up with a released slider value, so the thumb can follow the state again. */
fun seekSettled(released: Float, actual: Float, totalMs: Long): Boolean {
    if (totalMs <= 0L) return true
    // Within about two seconds of the target (at least half a percent).
    val tolerance = maxOf(0.005f, 2_000f / totalMs.toFloat())
    return abs(released - actual) <= tolerance
}

/** The clock time shown on a bookmark row. */
fun bookmarkTimeLabel(bookmark: Bookmark): String = formatClock(bookmark.timeSec * 1000L)

/** What a screen reader says for a bookmark row. */
fun bookmarkRowDescription(bookmark: Bookmark): String =
    "Bookmark ${bookmark.title}, at ${bookmarkTimeLabel(bookmark)}"

/** A history row: where the person was, and how long ago they left it. */
fun historyRowLabel(entry: HistoryEntry, nowMs: Long): String =
    "${formatClock(entry.positionMs)} \u00B7 ${historyAgoLabel(entry.recordedAt, nowMs)}"

/** What a screen reader says for a history row. */
fun historyRowDescription(entry: HistoryEntry, nowMs: Long): String =
    "Jump back to ${formatClock(entry.positionMs)}, ${historyAgoLabel(entry.recordedAt, nowMs)}"
