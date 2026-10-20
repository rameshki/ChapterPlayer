package com.chapterplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.browse.formatClock
import com.chapterplayer.domain.browse.formatTimeLeft
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.domain.playback.bookFraction
import com.chapterplayer.domain.playback.chapterFraction
import com.chapterplayer.domain.playback.nextChapterStart
import com.chapterplayer.domain.playback.previousChapterStart
import com.chapterplayer.domain.playback.sleepLabel
import com.chapterplayer.domain.playback.speedLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The full player: the playing book's state for the screen, and the controls. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val controller: PlaybackController,
    @Suppress("unused") private val starter: PlaybackStarter,
    books: BookRepository,
    private val settings: AppSettings,
    dispatcher: CoroutineDispatcher
) : ViewModel() {

    /** The error text the person dismissed; hidden until the text changes or clears. */
    private val dismissedError = MutableStateFlow<String?>(null)

    // The book of whatever is loaded, looked up again only when the loaded book changes.
    private val playingBook = controller.state.map { it.bookId }.distinctUntilChanged().flatMapLatest { id ->
        books.observeAllBooks().map { all -> if (id == null) null else all.firstOrNull { it.id == id } }
    }

    val uiState: StateFlow<PlayerUiState> = combine(
        controller.state, controller.sleepTimer, playingBook, dismissedError
    ) { state, timer, book, dismissed -> buildState(state, timer, book, dismissed) }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        // When the error goes away a dismissal is forgotten, so the same text can show again.
        viewModelScope.launch {
            controller.state.map { it.error }.distinctUntilChanged().collect { error ->
                if (error == null) dismissedError.value = null
            }
        }
    }

    private fun buildState(state: PlaybackState, timer: SleepTimer, book: AudioBook?, dismissed: String?): PlayerUiState {
        val chapters = state.chapters
        val index = state.chapterIndex?.takeIf { it in chapters.indices }
        val chapter = index?.let { chapters[it] }
        val position = state.positionMs
        val duration = state.durationMs
        return PlayerUiState(
            nothingPlaying = state.bookId == null,
            title = book?.title.orEmpty(),
            author = book?.author.orEmpty(),
            coverUrl = book?.coverUrl,
            chapterTitle = chapter?.title,
            bookFraction = bookFraction(position, duration),
            durationMs = duration.coerceAtLeast(0L),
            chapterFraction = if (chapter != null) chapterFraction(chapters, position) else null,
            elapsed = formatClock(position),
            remaining = formatClock(duration - position),
            chapterElapsed = chapter?.let { formatClock(position - it.startMs) },
            chapterRemaining = chapter?.let { formatClock(it.endMs - position) },
            timeLeft = if (duration > 0L) formatTimeLeft(duration, position) else "",
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            speed = state.speed,
            speedLabel = speedLabel(state.speed),
            sleepTimer = timer,
            sleepLabel = sleepLabel(timer, state.sleepRemainingMs),
            chapters = chapters.mapIndexed { i, c -> ChapterRow(i, c.title, c.startMs, c.durationMs, i == index) },
            currentChapterIndex = index,
            canGoPrevious = chapters.isNotEmpty(),
            canGoNext = nextChapterStart(chapters, position) != null,
            error = state.error?.takeIf { it != dismissed }
        )
    }

    fun playPause() {
        val state = controller.state.value
        if (state.bookId == null) return
        if (state.isPlaying || (state.isBuffering && state.playWhenReady)) controller.pause() else controller.play()
    }

    fun skipBack() = controller.skipBack()

    fun skipForward() = controller.skipForward()

    fun previousChapter() {
        val state = controller.state.value
        if (state.chapters.isEmpty()) return
        controller.seekTo(previousChapterStart(state.chapters, state.positionMs))
    }

    fun nextChapter() {
        val state = controller.state.value
        val start = nextChapterStart(state.chapters, state.positionMs) ?: return
        controller.seekTo(start)
    }

    fun seekBook(fraction: Float) {
        val state = controller.state.value
        if (state.bookId == null || fraction.isNaN()) return
        controller.seekTo((fraction.coerceIn(0f, 1f) * state.durationMs).toLong())
    }

    fun seekChapter(fraction: Float) {
        val state = controller.state.value
        if (fraction.isNaN()) return
        val chapter = state.chapterIndex?.let { state.chapters.getOrNull(it) } ?: return
        controller.seekTo(chapter.startMs + (fraction.coerceIn(0f, 1f) * chapter.durationMs).toLong())
    }

    fun jumpToChapter(index: Int) {
        val chapter = controller.state.value.chapters.getOrNull(index) ?: return
        controller.seekTo(chapter.startMs)
    }

    fun setSpeed(speed: Float) {
        if (speed.isNaN()) return
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        controller.setSpeed(clamped)
        viewModelScope.launch {
            try {
                settings.setPlaybackSpeed(clamped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The speed still applies to this session; it is only not remembered.
            }
        }
    }

    fun setSleepTimer(timer: SleepTimer) = controller.setSleepTimer(timer)

    fun dismissError() {
        dismissedError.value = controller.state.value.error
    }

    private companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
    }
}
