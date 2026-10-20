package com.chapterplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.browse.currentChapterIndex
import com.chapterplayer.domain.browse.formatClock
import com.chapterplayer.domain.browse.formatTimeLeft
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.domain.playback.bookFraction
import com.chapterplayer.domain.playback.MAX_PLAYBACK_SPEED
import com.chapterplayer.domain.playback.MIN_PLAYBACK_SPEED
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
        val index = currentIndex(state)
        val chapter = index?.let { chapters[it] }
        val position = state.positionMs
        val duration = state.durationMs
        return PlayerUiState(
            nothingPlaying = state.bookId == null,
            title = book?.title ?: if (state.bookId == null) "" else FALLBACK_TITLE,
            author = book?.author.orEmpty(),
            coverUrl = book?.coverUrl,
            chapterTitle = chapter?.title,
            bookFraction = bookFraction(position, duration),
            durationMs = duration.coerceAtLeast(0L),
            chapterFraction = chapter?.let { chapterFraction(it, position) },
            elapsed = formatClock(position),
            remaining = formatClock(duration - position),
            chapterElapsed = chapter?.let { formatClock(position - it.startMs) },
            chapterRemaining = chapter?.let { formatClock(it.endMs - position) },
            timeLeft = if (duration > 0L) formatTimeLeft(duration, position) else "",
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            showPause = state.isPlaying || (state.isBuffering && state.playWhenReady),
            speed = state.speed,
            speedLabel = speedLabel(state.speed),
            sleepTimer = timer,
            sleepLabel = sleepLabel(timer, state.sleepRemainingMs),
            chapters = chapters.mapIndexed { i, c -> ChapterRow(i, c.title, c.startMs, c.durationMs, i == index) },
            currentChapterIndex = index,
            canGoPrevious = chapters.isNotEmpty(),
            canGoNext = index != null && index < chapters.size - 1,
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
        val index = currentIndex(state) ?: return
        val current = state.chapters[index]
        // Past the first seconds of a chapter, Previous restarts it; near its start it goes to the one before.
        val target = if (state.positionMs - current.startMs > RESTART_AFTER_MS || index == 0) current.startMs
        else state.chapters[index - 1].startMs
        controller.seekTo(target)
    }

    fun nextChapter() {
        val state = controller.state.value
        val index = currentIndex(state) ?: return
        controller.seekTo((state.chapters.getOrNull(index + 1) ?: return).startMs)
    }

    fun seekBook(fraction: Float) {
        val state = controller.state.value
        if (state.bookId == null || fraction.isNaN()) return
        controller.seekTo((fraction.coerceIn(0f, 1f) * state.durationMs).toLong())
    }

    fun seekChapter(fraction: Float) {
        val state = controller.state.value
        if (fraction.isNaN()) return
        val chapter = currentIndex(state)?.let { state.chapters[it] } ?: return
        controller.seekTo(chapter.startMs + (fraction.coerceIn(0f, 1f) * chapter.durationMs).toLong())
    }

    fun jumpToChapter(index: Int) {
        val chapter = controller.state.value.chapters.getOrNull(index) ?: return
        controller.seekTo(chapter.startMs)
    }

    fun setSpeed(speed: Float) {
        if (speed.isNaN()) return
        val clamped = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
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

    /** The one source of "which chapter is current": the player's own index, else the one the position is in. */
    private fun currentIndex(state: PlaybackState): Int? =
        state.chapterIndex?.takeIf { it in state.chapters.indices } ?: currentChapterIndex(state.chapters, state.positionMs)

    private fun chapterFraction(chapter: Chapter, positionMs: Long): Float {
        if (chapter.durationMs <= 0L) return 0f
        return ((positionMs - chapter.startMs).toFloat() / chapter.durationMs.toFloat()).coerceIn(0f, 1f)
    }

    private companion object {
        const val FALLBACK_TITLE = "Now playing"
        const val RESTART_AFTER_MS = 3_000L
    }
}
