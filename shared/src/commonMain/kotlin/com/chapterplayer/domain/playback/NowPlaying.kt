package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.domain.browse.currentChapterIndex

/**
 * Calculates the progress fraction (0.0 to 1.0) within the current chapter.
 */
fun chapterFraction(
    chapters: List<Chapter>,
    positionMs: Long,
    index: Int? = currentChapterIndex(chapters, positionMs)
): Float? {
    if (index == null || index !in chapters.indices) return null
    val chapter = chapters[index]
    val duration = chapter.endMs - chapter.startMs
    if (duration <= 0) return 0f
    val fraction = (positionMs - chapter.startMs).toFloat() / duration.toFloat()
    return fraction.coerceIn(0f, 1f)
}

/**
 * Calculates the progress fraction (0.0 to 1.0) of the entire book.
 */
fun bookFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * Returns a formatted string representing the playback speed (e.g., "1.25x").
 */
fun speedLabel(speed: Float): String {
    val hundredths = (speed * 100f + 0.5f).toInt()
    val whole = hundredths / 100
    val frac = hundredths % 100
    return when {
        frac == 0 -> "$whole" + "x"
        frac % 10 == 0 -> "$whole.${frac / 10}x"
        else -> {
            val s = frac.toString()
            val padded = if (s.length < 2) "0$s" else s
            "$whole.$padded" + "x"
        }
    }
}

/**
 * Returns a formatted string for the sleep timer status or countdown.
 */
fun sleepLabel(timer: SleepTimer, remainingMs: Long?): String {
    return when (timer) {
        is SleepTimer.Off -> "Sleep timer"
        is SleepTimer.EndOfChapter -> "End of chapter"
        is SleepTimer.After -> {
            if (remainingMs == null) {
                "${timer.minutes} min"
            } else {
                val totalSeconds = (if (remainingMs < 0) 0L else remainingMs) / 1000
                val h = totalSeconds / 3600
                val m = (totalSeconds % 3600) / 60
                val s = totalSeconds % 60
                if (h > 0) {
                    "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
                } else {
                    "${m}:${s.toString().padStart(2, '0')}"
                }
            }
        }
    }
}

/**
 * The standard list of available sleep timer presets.
 */
val SLEEP_PRESETS: List<SleepTimer> = listOf(
    SleepTimer.Off,
    SleepTimer.After(15),
    SleepTimer.After(30),
    SleepTimer.After(45),
    SleepTimer.After(60),
    SleepTimer.EndOfChapter
)

/**
 * Returns a human-readable label for a sleep timer preset.
 */
fun sleepPresetLabel(timer: SleepTimer): String {
    return when (timer) {
        is SleepTimer.Off -> "Off"
        is SleepTimer.EndOfChapter -> "End of chapter"
        is SleepTimer.After -> {
            val m = timer.minutes
            if (m >= 60 && m % 60 == 0) {
                val h = m / 60
                if (h == 1) "1 hour" else "$h hours"
            } else {
                if (m == 1) "1 minute" else "$m minutes"
            }
        }
    }
}

/**
 * Returns the start timestamp for the previous chapter or restarts the current one.
 */
fun previousChapterStart(
    chapters: List<Chapter>,
    positionMs: Long,
    index: Int? = currentChapterIndex(chapters, positionMs)
): Long {
    if (chapters.isEmpty()) return 0L
    val i = index?.takeIf { it in chapters.indices } ?: (chapters.size - 1)
    val c = chapters[i]
    // If more than 3 seconds have passed in the chapter, restart it
    if (positionMs - c.startMs > 3000) return c.startMs
    return if (i == 0) c.startMs else chapters[i - 1].startMs
}

/**
 * Returns the start timestamp of the next chapter, or null if at the end.
 */
fun nextChapterStart(
    chapters: List<Chapter>,
    positionMs: Long,
    index: Int? = currentChapterIndex(chapters, positionMs)
): Long? {
    val i = index?.takeIf { it in chapters.indices } ?: return null
    if (i >= chapters.size - 1) return null
    return chapters[i + 1].startMs
}
