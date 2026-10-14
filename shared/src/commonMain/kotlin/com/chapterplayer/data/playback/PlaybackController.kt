package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays one audiobook at a time. Each platform provides an implementation: Media3 on
 * Android (Stage 2) and AVPlayer on iOS (Stage 5). Nothing implements it yet.
 */
interface PlaybackController {

    /** What is playing now, for the player screen and the mini player to observe. */
    val state: StateFlow<PlaybackState>

    /** Prepares [book] and seeks to [startMs]; does not start playing. */
    suspend fun load(book: AudioBook, startMs: Long)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** 1.0 is normal speed. */
    fun setSpeed(speed: Float)

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
    /** Index into the book's chapter list, or null when there is no book or it has no chapters. */
    val chapterIndex: Int? = null,
)
