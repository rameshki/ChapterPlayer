package com.chapterplayer.testing

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.playback.AudioOrigin
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.playback.SleepTimer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [PlaybackController] that records calls; tests move [playback] by hand. */
class FakePlaybackController : PlaybackController {
    val playback = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = playback
    /** What [activeSession] returns; tests set it to the session of the loaded book. */
    var session: PlaybackSession? = null
    override val activeSession: PlaybackSession? get() = session
    val sleep = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    override val sleepTimer: StateFlow<SleepTimer> = sleep

    /** Runs inside [load]; suspend on a gate or throw to simulate a slow or failing start. */
    var onLoad: suspend (AudioBook, Long) -> Unit = { book, _ ->
        playback.value = PlaybackState(bookId = book.id, durationMs = book.durationMs)
    }
    val loads = mutableListOf<Pair<String, Long>>()
    /** Where each load's audio came from, in the order of [loads]. */
    val origins = mutableListOf<AudioOrigin>()
    var plays = 0
    var pauses = 0
    val chapterSeeks = mutableListOf<Int>()

    override suspend fun load(book: AudioBook, startMs: Long, origin: AudioOrigin) {
        loads += book.id to startMs
        origins += origin
        calls += "load"
        onLoad(book, startMs)
    }

    /** Like the real player: play asks for playback and buffers; [soundStarts] is when it is really playing. */
    override fun play() {
        plays++
        calls += "play"
        playback.value = playback.value.copy(playWhenReady = true, isBuffering = true, isPlaying = false)
    }

    fun soundStarts() {
        playback.value = playback.value.copy(isBuffering = false, isPlaying = true)
    }

    override fun pause() {
        pauses++
        playback.value = playback.value.copy(isPlaying = false, isBuffering = false, playWhenReady = false)
    }

    val seeks = mutableListOf<Long>()
    override fun seekTo(positionMs: Long) { seeks += positionMs }
    val speeds = mutableListOf<Float>()
    val sleepSets = mutableListOf<SleepTimer>()
    var skipBacks = 0
    var skipForwards = 0
    /** Every control call in order, for checks like "speed was set after load and before play". */
    val calls = mutableListOf<String>()
    override fun setSpeed(speed: Float) { speeds += speed; calls += "speed" }
    override fun setSleepTimer(timer: SleepTimer) { sleepSets += timer }
    override fun skipBack() { skipBacks++ }
    override fun skipForward() { skipForwards++ }
    override fun seekToChapter(index: Int) { chapterSeeks += index }
    val releasedFiles = mutableListOf<String>()
    override fun releaseLocalFiles(bookId: String) { releasedFiles += bookId }
    override fun release() {}
}

class FakeConnectivity(online: Boolean = true) : ConnectivityMonitor {
    val online = MutableStateFlow(online)
    override val isOnline: Flow<Boolean> = this.online
}
