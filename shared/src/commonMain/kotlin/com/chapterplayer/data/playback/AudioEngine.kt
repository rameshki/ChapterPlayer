package com.chapterplayer.data.playback

/**
 * One file (or Plex track) of the loaded book, as a platform player plays it. Nothing in it is secret: the token
 * travels in [StreamHeaders], never in the address.
 */
data class EngineItem(
    val url: String,
    /** Empty when unknown (the player works it out). */
    val mimeType: String,
    /** The file's length on the book's timeline. */
    val durationMs: Long,
) {
    val isFile: Boolean get() = url.startsWith("file:", ignoreCase = true)
}

/** Why an item stopped playing, as far as the platform player says. */
enum class EngineFailure {
    NETWORK,
    /** HTTP 401: the token may have expired. */
    UNAUTHORIZED,
    /** HTTP 403: the account may not play this. */
    FORBIDDEN,
    NOT_FOUND,
    /** The phone would not give the app the audio (iOS: the audio session could not be made active). Nothing failed in the item. */
    AUDIO_SESSION,
    OTHER,
}

/**
 * The platform's audio player under [EnginePlaybackController] (iOS: AVQueuePlayer). It plays [EngineItem]s one after the
 * other with no gap the listener can hear and knows nothing about books, sessions, chapters or the sleep timer. Every call
 * and every [Listener] callback happens on the main thread.
 */
interface AudioEngine {

    interface Listener {
        /** Something the controller shows changed: playing, buffering, the item, a seek finished. */
        fun onChanged()

        /** Item [index] cannot play ([failure] says why). The engine stays where it was and stops asking for audio. */
        fun onItemFailed(index: Int, failure: EngineFailure)

        /** The last item played to its end; the engine has stopped there ([ended] is true). */
        fun onEnded()
    }

    fun setListener(listener: Listener?)

    /**
     * Replaces what the engine holds with [items] and prepares item [index] at [offsetMs]; does not start playing.
     * Network items are requested with [headers].
     */
    fun setQueue(items: List<EngineItem>, headers: StreamHeaders, index: Int, offsetMs: Long)

    /** Builds item [index] again at [offsetMs] with [headers] (after a refreshed token or a failure); plays on if it was. */
    fun reload(headers: StreamHeaders, index: Int, offsetMs: Long)

    /**
     * New headers for items built from now on (a refreshed token). The item already queued after the playing one is built
     * again with them; the playing one keeps its own (a 401 there is rebuilt by the controller at its position).
     */
    fun updateHeaders(headers: StreamHeaders)

    /** Empties the engine. */
    fun clear()

    /** Plays at [rate] (1.0 is normal speed) once the audio is there. */
    fun play(rate: Float)

    fun pause()

    /** Changes the speed; applies at once when playing. */
    fun setRate(rate: Float)

    /** Moves to [offsetMs] in item [index] (any item). */
    fun seekTo(index: Int, offsetMs: Long)

    /** The item playing (or prepared). */
    val currentIndex: Int

    /** The position in [currentIndex], including a seek still under way. */
    val currentOffsetMs: Long

    /** Sound is coming out. */
    val isPlaying: Boolean

    /** Playing was asked for and the audio is not there yet. */
    val isBuffering: Boolean

    /** Playing was asked for (true while buffering, false once paused or ended). */
    val playWhenReady: Boolean

    /** The last item played to its end. A seek clears it. */
    val ended: Boolean

    /** Frees the player; the engine cannot be used afterwards. */
    fun release()
}
