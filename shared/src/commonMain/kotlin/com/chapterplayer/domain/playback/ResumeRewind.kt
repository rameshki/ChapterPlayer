package com.chapterplayer.domain.playback

/**
 * Remembers when playback paused so that the next resume can step back a little ([resumeStart]).
 * A fresh start (a new playlist) never rewinds. Times are any steady clock in milliseconds.
 */
class ResumeRewind {
    private var pausedAtMs: Long? = null

    /** Playback paused at [nowMs]. A second pause report while still paused keeps the first time. */
    fun paused(nowMs: Long) {
        if (pausedAtMs == null) pausedAtMs = nowMs
    }

    /** A new playlist was loaded (or playback resumed by other means): forget the pause. */
    fun reset() {
        pausedAtMs = null
    }

    /**
     * Playback is resuming at [positionMs] at [nowMs]. Returns the position to seek to first, or null when
     * no seek is needed. Forgets the pause either way.
     */
    fun resumed(positionMs: Long, nowMs: Long): Long? {
        val since = pausedAtMs ?: return null
        pausedAtMs = null
        val target = resumeStart(positionMs, nowMs - since)
        return if (target != positionMs) target else null
    }
}
