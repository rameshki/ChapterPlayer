package com.chapterplayer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.BrowseSort
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.chipFor
import com.chapterplayer.domain.browse.applyQuery
import com.chapterplayer.domain.browse.isDimmed
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * @param inProgress started, unfinished books, last played first.
 * @param recentlyAdded the newest books (unknown dates last).
 * @param isSyncing a library sync is running (so an empty Home means "loading", not "nothing").
 * @param hasBooks whether any book is cached, before the filters; tells "nothing yet" from "filtered out".
 */
data class HomeUiState(
    val inProgress: List<BrowseItem> = emptyList(),
    val recentlyAdded: List<BrowseItem> = emptyList(),
    val hasServers: Boolean = false,
    val isLoading: Boolean = true,
    val hasBooks: Boolean = false,
    /** The Downloaded filter is on (and no other), so an empty Home means nothing is on the phone. */
    val downloadedFilterOn: Boolean = false,
    val online: Boolean = true,
    val downloadedOnly: Boolean = false,
    val isSyncing: Boolean = false
) {
    /** Whether this book cannot play now (offline or Downloaded only, and not on the phone). */
    fun dimmed(item: BrowseItem): Boolean = isDimmed(item.downloadState, online, downloadedOnly)
}

class HomeViewModel(
    books: BookRepository,
    servers: ServerRepository,
    downloads: DownloadStates,
    queryStore: BrowseQueryStore,
    connectivity: ConnectivityMonitor,
    settings: AppSettings,
    syncCoordinator: LibrarySyncCoordinator,
    dispatcher: CoroutineDispatcher
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        observeBrowseItems(books, downloads),
        servers.observeAll(),
        queryStore.query,
        combine(connectivity.isOnline, settings.downloadedOnly, syncCoordinator.state) { online, downloadedOnly, sync ->
            Triple(online, downloadedOnly, sync.isSyncing)
        }
    ) { items, serverList, query, (online, downloadedOnly, syncing) ->
        HomeUiState(
            inProgress = inProgressShelf(items, query),
            recentlyAdded = recentlyAddedShelf(items, query),
            hasServers = serverList.isNotEmpty(),
            isLoading = false,
            hasBooks = items.isNotEmpty(),
            downloadedFilterOn = chipFor(query) == FilterChip.DOWNLOADED,
            online = online,
            downloadedOnly = downloadedOnly,
            isSyncing = syncing
        )
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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
