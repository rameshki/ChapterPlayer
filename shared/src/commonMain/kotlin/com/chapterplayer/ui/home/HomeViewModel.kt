package com.chapterplayer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.BrowseSort
import com.chapterplayer.domain.browse.ResumeInfo
import com.chapterplayer.domain.browse.continueSeries
import com.chapterplayer.domain.browse.resumeCandidate
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.chipFor
import com.chapterplayer.domain.browse.applyQuery
import com.chapterplayer.domain.browse.pruneServerFilter
import com.chapterplayer.domain.browse.isDimmed
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * @param resume the most recently played unfinished book, for the Resume card; never hidden by a browse filter.
 * @param continueSeries the next book of each series being read; never hidden by a browse filter.
 * @param inProgress started, unfinished books other than [resume], last played first.
 * @param playError a safe message when Resume could not start the book.
 * @param starting a Resume start is under way.
 * @param recentlyAdded the newest books (unknown dates last).
 * @param isSyncing a library sync is running (so an empty Home means "loading", not "nothing").
 * @param hasBooks whether any book is cached, before the filters; tells "nothing yet" from "filtered out".
 */
data class HomeUiState(
    val resume: ResumeInfo? = null,
    val continueSeries: List<BrowseItem> = emptyList(),
    val playError: String? = null,
    val starting: Boolean = false,
    val inProgress: List<BrowseItem> = emptyList(),
    val recentlyAdded: List<BrowseItem> = emptyList(),
    val hasServers: Boolean = false,
    val isLoading: Boolean = true,
    val hasBooks: Boolean = false,
    /** The Downloaded filter is on (and no other), so an empty Home means nothing is on the phone. */
    val downloadedFilterOn: Boolean = false,
    val online: Boolean = true,
    val downloadedOnly: Boolean = false,
    val isSyncing: Boolean = false,
    /** The Library's server filter hides some books here too; Home says so and offers to clear it. */
    val serverFilterOn: Boolean = false
) {
    /** Whether this book cannot play now (offline or Downloaded only, and not on the phone). */
    fun dimmed(item: BrowseItem): Boolean = isDimmed(item.downloadState, online, downloadedOnly)
}

class HomeViewModel(
    books: BookRepository,
    servers: ServerRepository,
    downloads: DownloadStates,
    private val queryStore: BrowseQueryStore,
    connectivity: ConnectivityMonitor,
    settings: AppSettings,
    syncCoordinator: LibrarySyncCoordinator,
    private val starter: PlaybackStarter,
    dispatcher: CoroutineDispatcher,
    merging: SourceMerging = SourceMerging.None
) : ViewModel() {

    private class Local(val starting: Boolean = false, val error: String? = null)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<HomeUiState> = combine(
        observeBrowseItems(books, downloads, merging),
        servers.observeAll(),
        queryStore.query,
        combine(connectivity.isOnline, settings.downloadedOnly, syncCoordinator.state) { online, downloadedOnly, sync ->
            Triple(online, downloadedOnly, sync.isSyncing)
        },
        local
    ) { items, serverList, rawQuery, (online, downloadedOnly, syncing), local ->
        val query = pruneServerFilter(rawQuery, serverList.mapTo(mutableSetOf()) { it.id })
        // The card and the series shelf come from every book, so a saved filter never hides them.
        val resume = resumeCandidate(items)
        // The entry's id: the card resumes one copy, but the shelves list the entry.
        val resumeId = resume?.entry?.book?.id
        HomeUiState(
            resume = resume,
            continueSeries = continueSeries(items, excludeBookId = resumeId),
            playError = local.error,
            starting = local.starting,
            inProgress = inProgressShelf(items, query).filter { it.book.id != resumeId },
            recentlyAdded = recentlyAddedShelf(items, query),
            hasServers = serverList.isNotEmpty(),
            isLoading = false,
            hasBooks = items.isNotEmpty(),
            downloadedFilterOn = chipFor(query) == FilterChip.DOWNLOADED && query.serverIds.isEmpty(),
            online = online,
            downloadedOnly = downloadedOnly,
            isSyncing = syncing,
            serverFilterOn = query.serverIds.isNotEmpty()
        )
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Starts [book] where this phone left off. [onStarted] runs when playback began, so the caller can
     * open the player; a failure shows as [HomeUiState.playError] with a safe message.
     */
    fun resume(book: AudioBook, onStarted: () -> Unit = {}) {
        if (local.value.starting) return
        local.value = Local(starting = true)
        viewModelScope.launch {
            val result = try {
                starter.start(book, null)
            } catch (e: CancellationException) {
                local.value = Local()
                throw e
            }
            local.value = result.fold(
                onSuccess = { Local() },
                onFailure = { Local(error = it.message ?: PlaybackStartException.DEFAULT_MESSAGE) }
            )
            if (result.isSuccess) onStarted()
        }
    }

    /** Shows every server's books again (the hint's button). */
    fun onServerFilterCleared() = queryStore.update { it.copy(serverIds = emptySet()) }

    fun dismissError() {
        if (!local.value.starting) local.value = Local()
    }

    private companion object {
        const val RECENT_LIMIT = 20

        // The user's filters apply; the shelves keep their own order whatever the Library sort is.
        fun inProgressShelf(items: List<BrowseItem>, query: BrowseQuery) =
            applyQuery(items, query.copy(sort = BrowseSort.RECENTLY_PLAYED))
                .filter { item -> item.progress.let { it != null && it.positionMs > 0 && !it.isFinished } }

        fun recentlyAddedShelf(items: List<BrowseItem>, query: BrowseQuery) =
            applyQuery(items, query.copy(sort = BrowseSort.RECENTLY_ADDED)).take(RECENT_LIMIT)
    }
}
