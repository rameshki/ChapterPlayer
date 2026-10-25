package com.chapterplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.PersonLink
import com.chapterplayer.domain.browse.SeriesLink
import com.chapterplayer.domain.browse.bookLinks
import com.chapterplayer.domain.playback.MarkAction
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.domain.playback.markActionFor
import com.chapterplayer.ui.details.DetailsDownloadPhase
import com.chapterplayer.ui.details.detailsDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the download item of the player menu does when tapped. HIDDEN: the book cannot be downloaded (Plex), no item. */
enum class MenuDownloadKind { HIDDEN, DOWNLOAD, TRY_AGAIN, PAUSE, RESUME, REMOVE }

/** The download item of the player menu: its label, whether it can be tapped and the state line under it. */
data class MenuDownload(
    val kind: MenuDownloadKind = MenuDownloadKind.HIDDEN,
    val label: String = "",
    val enabled: Boolean = false,
    /** "42% · 3h 10m left", "Downloaded · 1.2 GB", or why it is disabled; null when there is nothing to add. */
    val subtitle: String? = null,
)

/** Everything the player's overflow menu shows for the loaded book. */
data class PlayerMenuState(
    /** The loaded book; null when nothing is loaded. */
    val bookId: String? = null,
    /** The author with other books in the library: "More by <author>" opens that list. Null when there is none. */
    val moreBy: PersonLink? = null,
    /** The book's series; null for a book without one. */
    val series: SeriesLink? = null,
    val download: MenuDownload = MenuDownload(),
    /** "Mark as finished" or "Mark as not finished"; null when the book has no such state (Plex). */
    val mark: MarkAction? = null,
    /** "Remove download" was tapped and waits for the confirmation. */
    val confirmingRemove: Boolean = false,
    /** A short result text for a snackbar; the screen calls [PlayerMenuViewModel.onMessageShown] once it has shown it. */
    val message: String? = null,
) {
    val moreByLabel: String? get() = moreBy?.let { "More by ${it.title}" }
}

/** The ⋮ menu of the full player: what it offers for the loaded book, and its actions (download, mark as finished). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerMenuViewModel(
    private val controller: PlaybackController,
    private val books: BookRepository,
    overview: DownloadOverview,
    private val downloadController: DownloadController,
    connectivity: ConnectivityMonitor,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val confirmingRemove = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private data class Facts(val book: AudioBook?, val all: List<AudioBook>, val mark: MarkAction, val download: com.chapterplayer.data.downloads.DownloadRow?, val online: Boolean)

    private val facts = controller.state.map { it.bookId }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) {
            flowOf(Facts(null, emptyList(), MarkAction.MarkFinished, null, true))
        } else {
            combine(
                books.observeAllBooks(), books.observeProgress(id), overview.observeBook(id), connectivity.isOnline
            ) { all, progress, download, online ->
                Facts(all.firstOrNull { it.id == id }, all, markActionFor(progress), download, online)
            }
        }
    }

    val state: StateFlow<PlayerMenuState> = combine(facts, confirmingRemove, message) { facts, confirming, text ->
        val book = facts.book ?: return@combine PlayerMenuState(message = text)
        val links = bookLinks(facts.all.map { BrowseItem(it, null, DownloadState.NOT_DOWNLOADED) }, book)
        val download = menuDownload(book.source, facts)
        PlayerMenuState(
            bookId = book.id,
            moreBy = links.authors.firstOrNull(),
            series = links.series,
            download = download,
            mark = facts.mark,
            confirmingRemove = confirming && download.kind == MenuDownloadKind.REMOVE,
            message = text,
        )
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerMenuState())

    init {
        // A pending "Remove download?" belongs to one book's finished download: any change of book or of the item's kind cancels it.
        viewModelScope.launch {
            state.map { it.bookId to it.download.kind }.distinctUntilChanged().drop(1).collect { confirmingRemove.value = false }
        }
    }

    private fun menuDownload(source: ServerSource, facts: Facts): MenuDownload {
        val control = detailsDownload(source, facts.download, facts.online, knownSizeBytes = null, confirmingRemove = false)
        val subtitle = control.statusText ?: control.disabledReason
        return when (control.phase) {
            DetailsDownloadPhase.HIDDEN -> MenuDownload()
            DetailsDownloadPhase.IDLE -> MenuDownload(MenuDownloadKind.DOWNLOAD, "Download", control.canStart, subtitle)
            DetailsDownloadPhase.FAILED -> MenuDownload(MenuDownloadKind.TRY_AGAIN, "Try download again", control.canStart, subtitle)
            DetailsDownloadPhase.QUEUED, DetailsDownloadPhase.DOWNLOADING -> MenuDownload(MenuDownloadKind.PAUSE, "Pause download", true, subtitle)
            DetailsDownloadPhase.PAUSED -> MenuDownload(MenuDownloadKind.RESUME, "Resume download", true, subtitle)
            DetailsDownloadPhase.DONE -> MenuDownload(MenuDownloadKind.REMOVE, "Remove download", true, subtitle)
        }
    }

    /** The download item: starts, pauses, resumes or retries; for a finished download it asks before removing. */
    fun onDownloadClicked() {
        val current = state.value
        val bookId = current.bookId ?: return
        val item = current.download
        if (!item.enabled) return
        when (item.kind) {
            MenuDownloadKind.DOWNLOAD -> downloadAction { downloadController.enqueue(bookId) }
            MenuDownloadKind.TRY_AGAIN, MenuDownloadKind.RESUME -> downloadAction { downloadController.resume(bookId) }
            MenuDownloadKind.PAUSE -> downloadAction { downloadController.pause(bookId) }
            MenuDownloadKind.REMOVE -> confirmingRemove.value = true
            MenuDownloadKind.HIDDEN -> Unit
        }
    }

    fun onRemoveConfirmed() {
        if (!confirmingRemove.value) return
        confirmingRemove.value = false
        val bookId = state.value.bookId ?: return
        downloadAction { downloadController.remove(bookId) }
    }

    fun onRemoveDismissed() {
        confirmingRemove.value = false
    }

    private fun downloadAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message.value = DOWNLOAD_ERROR
            }
        }
    }

    /** Marks the loaded book finished, or not finished when it already is; the result shows as a message. */
    fun onMarkClicked() {
        val current = state.value
        val bookId = current.bookId ?: return
        val action = current.mark ?: return
        val finished = action == MarkAction.MarkFinished
        viewModelScope.launch {
            val result = try {
                books.markFinished(bookId, finished)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.failure(IllegalStateException())
            }
            message.value = result.fold(
                onSuccess = { outcome ->
                    val done = if (finished) "Marked as finished" else "Marked as not finished"
                    if (outcome == MarkOutcome.QUEUED) "$done. It will reach the server when it is back." else done
                },
                onFailure = { MARK_ERROR }
            )
        }
    }

    fun onMessageShown() {
        message.value = null
    }

    private companion object {
        const val DOWNLOAD_ERROR = "Could not change the download. Try again."
        const val MARK_ERROR = "Could not update the book. Try again."
    }
}
