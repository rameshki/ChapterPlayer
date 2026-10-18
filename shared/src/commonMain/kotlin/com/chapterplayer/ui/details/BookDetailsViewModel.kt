package com.chapterplayer.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.browse.BookLinks
import com.chapterplayer.domain.browse.SeriesEntry
import com.chapterplayer.domain.browse.aboutInfo
import com.chapterplayer.domain.browse.bookLinks
import com.chapterplayer.domain.browse.chapterRows
import com.chapterplayer.domain.browse.formatLength
import com.chapterplayer.domain.browse.formatTimeLeft
import com.chapterplayer.domain.browse.seriesNeighbours
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

/** One book's details: the cached chapters at once, the live chapters and description when the server answers. */
class BookDetailsViewModel(
    private val bookId: String,
    private val books: BookRepository,
    downloads: DownloadStates,
    dispatcher: CoroutineDispatcher
) : ViewModel() {

    private class Live(val details: BookDetails? = null, val loading: Boolean = false, val error: String? = null)

    private class Whole(val book: AudioBook?, val series: List<SeriesEntry>, val links: BookLinks)

    private val live = MutableStateFlow(Live())
    private val selectedTab = MutableStateFlow(DetailsTab.CHAPTERS)
    private var loadJob: Job? = null

    // Series and links come from every book of the library, never from the filtered lists.
    private val whole = observeBrowseItems(books, downloads).map { all ->
        val book = all.firstOrNull { it.book.id == bookId }?.book
        if (book == null) Whole(null, emptyList(), BookLinks(null, emptyList(), emptyList()))
        else Whole(book, seriesNeighbours(all, book), bookLinks(all, book))
    }.flowOn(dispatcher)

    val uiState: StateFlow<BookDetailsUiState> = combine(
        whole, books.observeProgress(bookId), live, selectedTab
    ) { whole, progress, live, tab ->
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
            BookDetailsUiState(
                phase = DetailsPhase.LOADED,
                book = book,
                chapters = chapterRows(chapters, progress),
                about = aboutInfo(book, live.details),
                series = whole.series,
                links = whole.links,
                timeLeft = timeLeft,
                lengthText = formatLength(book.durationMs),
                loadingDetails = live.loading,
                error = live.error,
                selectedTab = tab,
                resumeEnabled = false
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailsUiState())

    init {
        loadJob = viewModelScope.launch { fetchIfCached() }
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
        if (books.observeAllBooks().first().any { it.id == bookId }) fetch()
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
