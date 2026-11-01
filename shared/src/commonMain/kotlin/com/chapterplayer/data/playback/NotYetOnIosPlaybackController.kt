package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.domain.playback.SleepTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a start says on a build without a player ([com.chapterplayer.data.PlatformFeatures.playback] off). */
const val PLAYBACK_NOT_ON_IOS_TEXT = "Playback is not available on iOS yet"

/**
 * A [PlaybackController] for a build without a player (the iOS stand-in of Stage 5 step 2, kept for tests): nothing is ever
 * loaded (the mini player stays hidden), every [load] fails with [PLAYBACK_NOT_ON_IOS_TEXT] (shown as it is), and the
 * transport calls do nothing. The sleep timer is kept as set, so the sheet shows what was chosen.
 */
class NotYetOnIosPlaybackController : PlaybackController {
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState()).asStateFlow()

    override val activeSession: PlaybackSession? get() = null

    private val timer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    override val sleepTimer: StateFlow<SleepTimer> = timer.asStateFlow()

    override suspend fun load(book: AudioBook, startMs: Long, origin: AudioOrigin) {
        throw PlaybackStartException(PLAYBACK_NOT_ON_IOS_TEXT)
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit

    override fun setSleepTimer(timer: SleepTimer) {
        this.timer.value = timer
    }

    override fun skipBack() = Unit
    override fun skipForward() = Unit
    override fun seekToChapter(index: Int) = Unit
    override fun releaseLocalFiles(bookId: String) = Unit
    override fun release() = Unit
}
