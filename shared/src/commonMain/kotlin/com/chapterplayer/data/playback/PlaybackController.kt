package com.chapterplayer.data.playback

import com.chapterplayer.data.downloads.LocalFile
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.domain.playback.SleepTimer
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays one audiobook at a time. Each platform provides an implementation: Media3 on
 * Android ([com.chapterplayer.data.playback.AndroidPlaybackController]) and AVPlayer on iOS (Stage 5).
 * Call it from the main thread.
 */
interface PlaybackController {

    /** What is playing now, for the player screen and the mini player to observe. */
    val state: StateFlow<PlaybackState>

    /**
     * The server session of the loaded book; null when nothing is loaded or a downloaded book plays without one.
     * Progress sync reports against it (and queues the position when there is none).
     */
    val activeSession: PlaybackSession?

    /** The sleep timer in force. [SleepTimer.Off] when none is set. */
    val sleepTimer: StateFlow<SleepTimer>

    /**
     * Loads [book] from [origin], prepares it and seeks to [startMs]; does not start playing.
     * A negative [startMs] means "where the server says listening should resume" (the start without a session).
     *
     * @throws PlaybackStartException when playback cannot start; its message is safe to show.
     */
    suspend fun load(book: AudioBook, startMs: Long, origin: AudioOrigin = AudioOrigin.Server)

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

    /**
     * The downloaded files of [bookId] are about to be deleted: when the player is reading them, it stops and forgets
     * the book (as when the service stops), so the next Play loads it again. Anything else is left alone.
     */
    fun releaseLocalFiles(bookId: String)

    /** Frees the player. The controller cannot be used afterwards. */
    fun release()
}

/** Where a loaded book's audio comes from. */
sealed interface AudioOrigin {
    /** Streamed from the server, through a playback session. */
    data object Server : AudioOrigin

    /**
     * The downloaded [files] on this phone (in book order). With [openSession] (online) a server session is still
     * opened so listening syncs live; if it cannot be opened the book plays without one.
     */
    data class Phone(val files: List<LocalFile>, val openSession: Boolean) : AudioOrigin
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
    /** Playing was asked for: true while buffering before the first sound, false once paused. */
    val playWhenReady: Boolean = false,
    /** Why playback stopped, in words that are safe to show. Never holds a token or an address. */
    val error: String? = null,
    /** Playing time left before the sleep timer pauses playback; null when no timer runs. */
    val sleepRemainingMs: Long? = null,
    /** The chapters of the playing book. */
    val chapters: List<Chapter> = emptyList(),
)
