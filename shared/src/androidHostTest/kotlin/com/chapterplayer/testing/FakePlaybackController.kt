package com.chapterplayer.testing

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.playback.SleepTimer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [PlaybackController] that records calls; tests move [playback] by hand. */
class FakePlaybackController : PlaybackController {
    val playback = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = playback
    override val sleepTimer: StateFlow<SleepTimer> = MutableStateFlow(SleepTimer.Off)

    /** Runs inside [load]; suspend on a gate or throw to simulate a slow or failing start. */
    var onLoad: suspend (AudioBook, Long) -> Unit = { book, _ ->
        playback.value = PlaybackState(bookId = book.id, durationMs = book.durationMs)
    }
    val loads = mutableListOf<Pair<String, Long>>()
    var plays = 0
    var pauses = 0
    val chapterSeeks = mutableListOf<Int>()

    override suspend fun load(book: AudioBook, startMs: Long) {
        loads += book.id to startMs
        onLoad(book, startMs)
    }

    /** Like the real player: play asks for playback and buffers; [soundStarts] is when it is really playing. */
    override fun play() {
        plays++
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
    override fun setSpeed(speed: Float) {}
    override fun setSleepTimer(timer: SleepTimer) {}
    override fun skipBack() {}
    override fun skipForward() {}
    override fun seekToChapter(index: Int) { chapterSeeks += index }
    override fun release() {}
}

class FakeConnectivity(online: Boolean = true) : ConnectivityMonitor {
    val online = MutableStateFlow(online)
    override val isOnline: Flow<Boolean> = this.online
}
