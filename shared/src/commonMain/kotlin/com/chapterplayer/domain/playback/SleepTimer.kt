package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.domain.browse.currentChapterIndex

/**
 * Represents the configuration for a sleep timer that determines when playback should stop.
 */
sealed interface SleepTimer {
    /**
     * Indicates that the sleep timer is disabled.
     */
    data object Off : SleepTimer

    /**
     * Indicates that playback should stop after a specific number of minutes have elapsed.
     * @property minutes The duration in minutes from the start of playback.
     */
    data class After(val minutes: Int) : SleepTimer

    /**
     * Indicates that playback should stop at the end of the current chapter.
     */
    data object EndOfChapter : SleepTimer
}

/**
 * Calculates the remaining wall-clock time in milliseconds before the sleep timer triggers.
 * @return The remaining time in milliseconds, or null if the timer is Off or if EndOfChapter is used with no chapters.
 */
fun sleepRemainingMs(
    timer: SleepTimer,
    playedMs: Long,
    positionMs: Long,
    chapters: List<Chapter>,
    speed: Float
): Long? {
    return when (timer) {
        is SleepTimer.Off -> null
        is SleepTimer.After -> {
            val totalMs = timer.minutes * 60_000L
            // Ensure we do not return a negative value if played time exceeds the limit
            if (totalMs - playedMs > 0) totalMs - playedMs else 0L
        }
        is SleepTimer.EndOfChapter -> {
            val index = currentChapterIndex(chapters, positionMs) ?: return null
            val chapter = chapters[index]
            val remainingBookMs = if (chapter.endMs > positionMs) chapter.endMs - positionMs else 0L
            
            // Treat non-positive speeds as 1.0
            val effectiveSpeed = if (speed <= 0f) 1.0f else speed
            
            // Calculate wall clock time using ceiling to round up to the nearest whole millisecond
            kotlin.math.ceil(remainingBookMs.toDouble() / effectiveSpeed.toDouble()).toLong()
        }
    }
}
