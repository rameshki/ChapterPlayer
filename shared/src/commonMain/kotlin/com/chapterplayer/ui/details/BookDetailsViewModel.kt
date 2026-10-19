package com.chapterplayer.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.browse.BookLinks
import com.chapterplayer.domain.browse.SeriesEntry
import com.chapterplayer.domain.browse.aboutInfo
import com.chapterplayer.domain.browse.bookLinks
import com.chapterplayer.domain.browse.chapterRows
import com.chapterplayer.domain.browse.formatLength
import com.chapterplayer.domain.browse.formatTimeLeft
import com.chapterplayer.domain.browse.isPlayable
import com.chapterplayer.domain.browse.seriesNeighbours
import com.chapterplayer.domain.playback.isFinishedAt
import com.chapterplayer.domain.playback.shouldSaveProgress
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** One book's details: the cached chapters at once, the live chapters and description when the server answers. */
class BookDetailsViewModel(
    private val bookId: String,
    private val books: BookRepository,
    downloads: DownloadStates,
    private val controller: PlaybackController,
    connectivity: ConnectivityMonitor,
    settings: AppSettings,
    dispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : ViewModel() {

    private class Live(val details: BookDetails? = null, val loading: Boolean = false, val error: String? = null)

    private class Whole(
        val book: AudioBook?,
        val series: List<SeriesEntry>,
        val links: BookLinks,
        val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED
    )

    /** The play control's own state: a start under way and the last start failure. */
    private class Local(val starting: Boolean = false, val error: String? = null)

    private class Env(val player: PlaybackState, val local: Local, val online: Boolean, val downloadedOnly: Boolean)

    private val live = MutableStateFlow(Live())
    private val local = MutableStateFlow(Local())
    private val selectedTab = MutableStateFlow(DetailsTab.CHAPTERS)
    private var loadJob: Job? = null

    // Series and links come from every book of the library, never from the filtered lists.
    private val whole = observeBrowseItems(books, downloads).map { all ->
        val item = all.firstOrNull { it.book.id == bookId }
        val book = item?.book
        if (book == null) Whole(null, emptyList(), BookLinks(null, emptyList(), emptyList()))
        else Whole(book, seriesNeighbours(all, book), bookLinks(all, book), item.downloadState)
    }.flowOn(dispatcher)

    private val env = combine(
        controller.state, local, connectivity.isOnline, settings.downloadedOnly
    ) { player, local, online, downloadedOnly -> Env(player, local, online, downloadedOnly) }

    val uiState: StateFlow<BookDetailsUiState> = combine(
        whole, books.observeProgress(bookId), live, selectedTab, env
    ) { whole, progress, live, tab, env ->
        val book = whole.book
        if (book == null) {
            BookDetailsUiState(phase = DetailsPhase.NOT_FOUND, selectedTab = tab)
        } else {
            val chapters = live.details?.chapters ?: book.chapters
            val timeLeft = when {
                progress == null -> null
                progress.isFinished || progress.positionMs >= book.durationMs -> "Finished"
                progress.positionMs > 0 -> formatTimeLeft(book.durationMs, progress.positionMs)
                else -> null
            }
            val loaded = env.player.bookId == bookId
            val playback = when {
                env.local.starting -> DetailsPlayback.STARTING
                loaded && env.player.isPlaying -> DetailsPlayback.PLAYING
                loaded -> DetailsPlayback.PAUSED
                else -> DetailsPlayback.NOT_PLAYING
            }
            val canPlay = book.serverId.isNotBlank() &&
                isPlayable(whole.downloadState, env.online, env.downloadedOnly)
            // While this book is loaded in the player the highlight follows the live position.
            val shown = if (loaded) ReadingProgress(bookId, env.player.positionMs, book.durationMs, 0L) else progress
            val savedStart = when {
                progress == null || progress.isFinished || progress.positionMs >= book.durationMs -> 0L
                else -> progress.positionMs.coerceAtLeast(0L)
            }
            BookDetailsUiState(
                phase = DetailsPhase.LOADED,
                book = book,
                chapters = chapterRows(chapters, shown),
                about = aboutInfo(book, live.details),
                series = whole.series,
                links = whole.links,
                timeLeft = timeLeft,
                lengthText = formatLength(book.durationMs),
                loadingDetails = live.loading,
                detailsLoaded = live.details != null,
                error = live.error,
                selectedTab = tab,
                playback = playback,
                canPlay = canPlay,
                cannotPlayReason = if (canPlay) null else NEEDS_CONNECTION_TEXT,
                playError = env.local.error,
                currentPositionMs = if (loaded) env.player.positionMs else progress?.positionMs ?: 0L,
                resumePositionMs = savedStart
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailsUiState())

    init {
        loadJob = viewModelScope.launch { fetchIfCached() }
        viewModelScope.launch { saveOnPause() }
    }

    /** The play control: starts, pauses or resumes this book. */
    fun onResumeClicked() {
        if (local.value.starting) return
        val state = uiState.value
        val book = state.book ?: return
        when (state.playback) {
            DetailsPlayback.STARTING -> Unit
            DetailsPlayback.PLAYING -> controller.pause()
            DetailsPlayback.PAUSED -> {
                if (!state.canPlay) return
                local.value = Local()
                controller.play()
            }
            DetailsPlayback.NOT_PLAYING -> if (state.canPlay) start(book, state.resumePositionMs)
        }
    }

    /** Jumps to a chapter of the loaded book, or starts the book at that chapter. */
    fun onChapterClicked(index: Int) {
        if (local.value.starting) return
        val state = uiState.value
        val book = state.book ?: return
        val row = state.chapters.getOrNull(index) ?: return
        if (!chapterTapsEnabled(state.playback, state.canPlay)) return
        when (state.playback) {
            DetailsPlayback.PLAYING, DetailsPlayback.PAUSED -> controller.seekToChapter(index)
            DetailsPlayback.NOT_PLAYING -> start(book, row.startMs)
            DetailsPlayback.STARTING -> Unit
        }
    }

    fun dismissPlayError() {
        if (!local.value.starting) local.value = Local()
    }

    // `starting` is set before the coroutine runs, so a second tap cannot start a second load.
    private fun start(book: AudioBook, startMs: Long) {
        local.value = Local(starting = true)
        viewModelScope.launch {
            local.value = try {
                controller.load(book, startMs)
                controller.play()
                Local()
            } catch (e: CancellationException) {
                local.value = Local()
                throw e
            } catch (e: PlaybackStartException) {
                Local(error = e.message ?: PlaybackStartException.DEFAULT_MESSAGE)
            } catch (e: Exception) {
                Local(error = PlaybackStartException.DEFAULT_MESSAGE)
            }
        }
    }

    // Saves this book's position when its playback goes from playing to not playing.
    private suspend fun saveOnPause() {
        var previous: PlaybackState? = null
        controller.state.collect { current ->
            val before = previous
            previous = current
            if (before == null || !before.isPlaying || current.isPlaying || before.bookId != bookId) return@collect
            // Another book took over: the last position seen for this one is the best there is.
            val last = if (current.bookId == bookId) current else before
            val duration = last.durationMs
            if (!shouldSaveProgress(bookId, last.positionMs, duration)) return@collect
            try {
                books.saveProgress(
                    ReadingProgress(bookId, last.positionMs, duration, nowMs(), isFinishedAt(last.positionMs, duration))
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed local save must not stop the observation; the next pause tries again.
            }
        }
    }

    fun selectTab(tab: DetailsTab) {
        selectedTab.value = tab
    }

    fun retry() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { fetchIfCached() }
    }

    // An unknown or deleted book never asks the server.
    private suspend fun fetchIfCached() {
        // `whole` already runs on the injected dispatcher, so the lookup stays off the main thread.
        if (whole.first().book != null) fetch()
    }

    private suspend fun fetch() {
        live.value = Live(live.value.details, loading = true, error = null)
        val result = try {
            books.loadDetails(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        live.value = result.fold(
            onSuccess = { Live(it ?: live.value.details) },
            onFailure = { Live(live.value.details, error = errorText(it)) }
        )
    }

    private fun errorText(error: Throwable) =
        if (error is SignInRequiredException) "Could not load details. Sign in again in Settings."
        else "Could not load details. Showing what is saved."
}
