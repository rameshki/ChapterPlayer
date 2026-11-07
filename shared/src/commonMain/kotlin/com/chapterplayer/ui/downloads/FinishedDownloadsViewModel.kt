package com.chapterplayer.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.playback.upNextCanPlay
import kotlinx.coroutines.flow.onEach
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.upnext.NoUpNext
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.FinishedDownloads
import com.chapterplayer.domain.downloads.finishedDownloadsToRemove
import com.chapterplayer.domain.downloads.removeFinishedQuestion
import com.chapterplayer.domain.downloads.removeFinishedResult
import com.chapterplayer.domain.downloads.removeFinishedSummary
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Remove finished downloads": [summary] null hides the row (nothing qualifies); [question] is set while the
 * confirmation shows; [removing] while the downloads go.
 */
data class FinishedDownloadsUi(
    val summary: String? = null,
    val question: String? = null,
    val removing: Boolean = false,
)

/**
 * The "Remove finished downloads" row of the Downloads tab and of Settings, Downloads and offline: which downloads of
 * finished books would go (`finishedDownloadsToRemove`: never the book in the player or the one Up next starts next), the
 * confirmation, then [DownloadController.remove] for each, one after another, and one result [message].
 */
class FinishedDownloadsViewModel(
    books: BookRepository,
    downloads: DownloadStates,
    overview: DownloadOverview,
    private val controller: DownloadController,
    player: PlaybackController,
    dispatcher: CoroutineDispatcher,
    connectivity: ConnectivityMonitor,
    settings: AppSettings,
    merging: SourceMerging = SourceMerging.None,
    upNext: UpNextRepository = NoUpNext,
) : ViewModel() {

    /** What qualifies now, with the sizes of the finished downloads (by book id) for the freed figure. */
    private class Pick(val pick: FinishedDownloads, val sizes: Map<String, Long>)

    private val doneBytes = overview.observe()
        .map { rows -> rows.filter { it.status == DownloadStatus.DONE }.associate { it.bookId to maxOf(it.doneBytes, it.totalBytes) } }
        .distinctUntilChanged()

    private val loadedBookId = player.state.map { it.bookId }.distinctUntilChanged()

    // What decides whether a queued book can play now, as the Up next advancer sees it.
    private val playRule = combine(connectivity.isOnline, settings.downloadedOnly, merging.context) { online, downloadedOnly, context ->
        { member: BrowseItem -> upNextCanPlay(member, online, downloadedOnly, context?.onlineServerIds) }
    }

    // The latest pick while the row is on screen (the flows run only then, like the other list view models).
    private val latest = MutableStateFlow(Pick(FinishedDownloads(emptyList(), 0L), emptyMap()))

    private val candidates = combine(
        observeBrowseItems(books, downloads, merging), doneBytes, loadedBookId, upNext.queue, playRule,
    ) { items, bytes, loaded, queue, canPlay ->
        Pick(finishedDownloadsToRemove(items, bytes, loaded, queue, canPlay), bytes)
    }.flowOn(dispatcher).onEach { latest.value = it }

    private val confirming = MutableStateFlow(false)
    private val removing = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    /** The result of the removal, for a snackbar; see [onMessageShown]. */
    val message: StateFlow<String?> = _message

    val uiState: StateFlow<FinishedDownloadsUi> = combine(candidates, confirming, removing) { current, asking, busy ->
        val pick = current.pick
        // Nothing qualifies any more (a sync un-finished the book): the question goes and does not come back by itself.
        if (asking && pick.count == 0) confirming.value = false
        FinishedDownloadsUi(
            summary = removeFinishedSummary(pick),
            question = if (asking && pick.count > 0) removeFinishedQuestion(pick) else null,
            removing = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinishedDownloadsUi())

    fun onRemoveClicked() {
        if (latest.value.pick.count > 0 && !removing.value) confirming.value = true
    }

    fun onDismissed() {
        confirming.value = false
    }

    /** Removes what qualifies now (the list may have changed while the question showed). */
    fun onConfirmed() {
        if (!confirming.value) return
        confirming.value = false
        val current = latest.value
        val pick = current.pick
        if (pick.count == 0 || removing.value) return
        removing.value = true
        viewModelScope.launch {
            val sizes = current.sizes
            var removed = 0
            var failed = 0
            var freed = 0L
            for (id in pick.bookIds) {
                try {
                    controller.remove(id)
                    removed++
                    freed += sizes[id] ?: 0L
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }

            removing.value = false
            _message.value = removeFinishedResult(removed, failed, freed)
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}
