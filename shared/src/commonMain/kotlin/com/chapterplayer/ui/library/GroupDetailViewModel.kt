package com.chapterplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.domain.browse.BookGroup
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.DownloadFilter
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.domain.browse.applyQuery
import com.chapterplayer.domain.browse.isDimmed
import com.chapterplayer.domain.browse.isFiltering
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @param title the group's name; null when the group no longer exists.
 * @param exists whether the group has any book at all, before the filters (a deleted or emptied group does not).
 * @param items the group's books that pass the current filters.
 */
data class GroupDetailUiState(
    val title: String? = null,
    val exists: Boolean = false,
    val items: List<BrowseItem> = emptyList(),
    val layout: LibraryLayout = LibraryLayout.LARGE_GRID,
    val isFiltering: Boolean = false,
    val isLoading: Boolean = true,
    val online: Boolean = true,
    val downloadedOnly: Boolean = false,
    val nothingDownloaded: Boolean = false
) {
    /** The group has books but the filters hide them all. */
    val emptyBecauseOfFilter: Boolean get() = exists && items.isEmpty() && isFiltering

    fun dimmed(item: BrowseItem): Boolean = isDimmed(item.downloadState, online, downloadedOnly)
}

/** The books of one author, narrator, series or collection, under the same filters as the Library screen. */
class GroupDetailViewModel(
    private val kind: LibraryView,
    private val key: String,
    books: BookRepository,
    downloads: DownloadStates,
    private val queryStore: BrowseQueryStore,
    connectivity: ConnectivityMonitor,
    private val settings: AppSettings,
    dispatcher: CoroutineDispatcher
) : ViewModel() {

    /** The Downloaded chip is on and none of the group's books is on the phone (the Library list shows the same text). */
    private fun nothingDownloaded(query: BrowseQuery, shown: BookGroup?) =
        shown?.items.orEmpty().isEmpty() && query.download == DownloadFilter.DOWNLOADED && query.progress == ProgressFilter.ALL

    private class Found(val title: String?, val exists: Boolean, val items: List<BrowseItem>, val filtering: Boolean, val nothingDownloaded: Boolean)

    private class Whole(val all: List<BrowseItem>, val collections: List<BookCollection>, val group: BookGroup?)

    // The title and existence come from the unfiltered groups, so a filter never makes the group "vanish".
    // They do not depend on the filters, so they are built when the books change, not on every filter change.
    private val whole = combine(observeBrowseItems(books, downloads), books.observeCollections()) { all, collections ->
        Whole(all, collections, groupsFor(kind, applyQuery(all, BrowseQuery()), collections).firstOrNull { it.key == key })
    }

    private val found = combine(whole, queryStore.query) { whole, query ->
        // Without a filter the shown group is the unfiltered one, so the groups are built only once.
        val shown = if (query == BrowseQuery()) whole.group
        else groupsFor(kind, applyQuery(whole.all, query), whole.collections).firstOrNull { it.key == key }
        Found(whole.group?.title, whole.group != null, shown?.items.orEmpty(), isFiltering(query), nothingDownloaded(query, shown))
    }.flowOn(dispatcher)

    val uiState: StateFlow<GroupDetailUiState> = combine(
        found, settings.libraryLayout, connectivity.isOnline, settings.downloadedOnly
    ) { found, layout, online, downloadedOnly ->
        GroupDetailUiState(
            title = found.title,
            exists = found.exists,
            items = found.items,
            layout = layout,
            isFiltering = found.filtering,
            nothingDownloaded = found.nothingDownloaded,
            isLoading = false,
            online = online,
            downloadedOnly = downloadedOnly
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailUiState())

    fun onLayoutSelected(layout: LibraryLayout) {
        viewModelScope.launch { settings.setLibraryLayout(layout) }
    }

    fun onClearFilters() = queryStore.update { BrowseQuery() }
}
