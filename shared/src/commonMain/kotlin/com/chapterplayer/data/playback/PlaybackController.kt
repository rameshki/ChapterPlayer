package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.domain.playback.SleepTimer
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays one audiobook at a time. Each platform provides an implementation: Media3 on
 * Android ([com.chapterplayer.data.playback.AndroidPlaybackController]) and AVPlayer on iOS (Stage 5).
 */
interface PlaybackController {

    /** What is playing now, for the player screen and the mini player to observe. */
    val state: StateFlow<PlaybackState>

    /** The sleep timer in force. [SleepTimer.Off] when none is set. */
    val sleepTimer: StateFlow<SleepTimer>

    /**
     * Starts a playback session for [book], prepares it and seeks to [startMs]; does not start playing.
     * A negative [startMs] means "where the server says listening should resume".
     *
     * @throws PlaybackStartException when playback cannot start; its message is safe to show.
     */
    suspend fun load(book: AudioBook, startMs: Long)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** 1.0 is normal speed. */
    fun setSpeed(speed: Float)

    /** Sets, changes or (with [SleepTimer.Off]) clears the sleep timer. Time played counts from now. */
    fun setSleepTimer(timer: SleepTimer)

    /** Jumps back 15 seconds on the book's timeline. */
    fun skipBack()

    /** Jumps forward 30 seconds on the book's timeline. */
    fun skipForward()

    /** Jumps to the start of the chapter at [index] in [PlaybackState.chapters]; an unknown index does nothing. */
    fun seekToChapter(index: Int)

    /** Frees the player. The controller cannot be used afterwards. */
    fun release()
}

/** A snapshot of playback. Positions are milliseconds into the whole book. */
data class PlaybackState(
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    /** Index into [chapters], or null when there is no book or it has no chapters. */
    val chapterIndex: Int? = null,
    /** The player is waiting for audio to arrive. */
    val isBuffering: Boolean = false,
    /** Why playback stopped, in words that are safe to show. Never holds a token or an address. */
    val error: String? = null,
    /** Playing time left before the sleep timer pauses playback; null when no timer runs. */
    val sleepRemainingMs: Long? = null,
    /** The chapters of the playing book. */
    val chapters: List<Chapter> = emptyList(),
)
