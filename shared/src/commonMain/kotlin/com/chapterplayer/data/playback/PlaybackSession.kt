package com.chapterplayer.data.playback

import com.chapterplayer.data.model.Chapter

/**
 * One audio file of a book as the player will fetch it.
 *
 * @property index the file's order within the book.
 * @property startMs where this file begins on the book's timeline.
 * @property durationMs the file's length.
 * @property mimeType the file's media type, such as `audio/mp4`.
 * @property url the absolute address of the audio. It carries no token: the bearer is added per request.
 */
data class PlaybackTrack(
    val index: Int,
    val startMs: Long,
    val durationMs: Long,
    val mimeType: String,
    val url: String,
    /**
     * The server's id of this file when the address does not carry one (a Plex track's rating key); null for
     * Audiobookshelf, whose `/file/{ino}` address names the file. Used to match a session to downloaded files.
     */
    val fileId: String? = null,
)

/**
 * A playback session a server opened for one book. Hold [sessionId] to close it when playback ends.
 * Nothing in it is secret.
 *
 * @property serverId the configured server that owns the book.
 * @property bookId the app's id for the book.
 * @property startPositionMs where the server says listening should resume.
 * @property tracks the audio files in order.
 */
data class PlaybackSession(
    val sessionId: String,
    val serverId: String,
    val bookId: String,
    val durationMs: Long,
    val startPositionMs: Long,
    val tracks: List<PlaybackTrack>,
    val chapters: List<Chapter>,
)
