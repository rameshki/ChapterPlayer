package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.domain.browse.currentChapterIndex

/**
 * The sleep timer's state and decisions, kept free of the player so they can be tested. The controller
 * feeds it time played and positions, and pauses playback when [tick] says so.
 *
 * "After n minutes" counts time played. "End of chapter" aims at the end of the chapter the listener is
 * in when it is set, and again after each seek they make, so it always means the chapter they are in.
 * Natural progress past the target fires once and returns the timer to Off.
 */
class SleepTimerEngine {
    var timer: SleepTimer = SleepTimer.Off
        private set
    private var targetEndMs: Long? = null
    private var playedMs = 0L

    /** Sets, changes or clears the timer. Time played counts from now. */
    fun set(timer: SleepTimer, positionMs: Long, chapters: List<Chapter>) {
        this.timer = timer
        playedMs = 0L
        targetEndMs = if (timer == SleepTimer.EndOfChapter) chapterEnd(positionMs, chapters) else null
    }

    /** Back to Off, for example when another book loads. */
    fun reset() = set(SleepTimer.Off, 0L, emptyList())

    /** Time spent playing since the last change, in wall-clock milliseconds. */
    fun addPlayed(deltaMs: Long) {
        if (deltaMs > 0) playedMs += deltaMs
    }

    /** The listener moved the position themselves (seek, skip, chapter jump): aim at the chapter they are in now. */
    fun userSeeked(positionMs: Long, chapters: List<Chapter>) {
        if (timer == SleepTimer.EndOfChapter) targetEndMs = chapterEnd(positionMs, chapters)
    }

    /** Playing time left, or null when no timer runs (or "end of chapter" in a book without chapters). */
    fun remainingMs(positionMs: Long, chapters: List<Chapter>, speed: Float): Long? = when (timer) {
        SleepTimer.EndOfChapter -> targetEndMs?.let { sleepRemainingToMs(it, positionMs, speed) }
        else -> sleepRemainingMs(timer, playedMs, positionMs, chapters, speed)
    }

    /** True exactly once when the timer has run out; the timer is then Off. */
    fun tick(positionMs: Long, chapters: List<Chapter>, speed: Float): Boolean {
        if (remainingMs(positionMs, chapters, speed) != 0L) return false
        reset()
        return true
    }

    private fun chapterEnd(positionMs: Long, chapters: List<Chapter>): Long? =
        currentChapterIndex(chapters, positionMs)?.let { chapters[it].endMs }
}
