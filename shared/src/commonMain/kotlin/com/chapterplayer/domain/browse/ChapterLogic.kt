package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.Chapter

/**
 * Returns the index of the current chapter based on the provided millisecond position.
 */
fun currentChapterIndex(chapters: List<Chapter>, positionMs: Long): Int? {
    if (chapters.isEmpty()) return null

    // Find the index of the last chapter that has already started.
    var lastStartedIndex = 0
    for (i in chapters.indices) {
        if (chapters[i].startMs <= positionMs) {
            lastStartedIndex = i
        } else {
            break
        }
    }

    val current = chapters[lastStartedIndex]

    // If position is before the first chapter's start or negative, return index 0.
    if (positionMs < current.startMs) return 0

    // If position is at or past the end of the last chapter, return last index.
    if (positionMs >= chapters.last().endMs) return chapters.size - 1

    // If the current chapter's end is after the position, it is the active chapter.
    // In gaps, the last chapter that started remains the current one.
    return lastStartedIndex
}

/**
 * Formats milliseconds into a clock string like M:SS or H:MM:SS.
 */
fun formatClock(ms: Long): String {
    val totalSeconds = (if (ms < 0) 0L else ms) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60

    val sStr = if (s < 10) "0$s" else "$s"

    return if (h > 0) {
        val mStr = if (m < 10) "0$m" else "$m"
        "$h:$mStr:$sStr"
    } else {
        "$m:$sStr"
    }
}

/**
 * Formats a duration into a human-readable length string.
 */
fun formatLength(ms: Long): String {
    if (ms <= 0) return "0 min"
    if (ms < 60_000) return "under 1 min"

    val totalMinutes = ms / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60

    return if (h > 0) {
        if (m == 0L) "$h h" else "$h h ${if (m < 10) "0$m" else "$m"} min"
    } else {
        "$m min"
    }
}

/**
 * Formats the remaining time until a duration is reached.
 */
fun formatTimeLeft(durationMs: Long, positionMs: Long): String {
    val remainder = durationMs - (if (positionMs < 0) 0L else positionMs)
    return if (remainder <= 0) {
        "finished"
    } else {
        "${formatLength(remainder)} left"
    }
}
