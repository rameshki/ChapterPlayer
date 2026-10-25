package com.chapterplayer.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.domain.browse.BookGroup
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.BrowseSort
import com.chapterplayer.domain.browse.DownloadFilter
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.LetterIndex
import com.chapterplayer.domain.browse.fastScrollIndex
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.domain.browse.applyQuery
import com.chapterplayer.domain.browse.pruneServerFilter
import com.chapterplayer.domain.browse.queryFor
import com.chapterplayer.domain.browse.serverChipLabel
import com.chapterplayer.domain.browse.serverFilterOptions
import com.chapterplayer.domain.browse.toggleServer
import com.chapterplayer.domain.browse.ServerFilterOption
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    books: BookRepository,
    servers: ServerRepository,
    downloads: DownloadStates,
    private val queryStore: BrowseQueryStore,
    connectivity: ConnectivityMonitor,
    private val settings: AppSettings,
    private val syncCoordinator: LibrarySyncCoordinator,
    dispatcher: CoroutineDispatcher,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    merging: SourceMerging = SourceMerging.None
) : ViewModel() {

    // Kept in the saved state so the chosen view comes back after the process was killed.
    private val _view = MutableStateFlow(
        LibraryView.entries.firstOrNull { it.name == savedState.get<String>(VIEW_KEY) } ?: LibraryView.LIBRARY
    )

    /** What the lists show, worked out off the main thread. */
    private class Content(
        val view: LibraryView,
        val query: BrowseQuery,
        val items: List<BrowseItem>,
        val groups: List<BookGroup>,
        val hasBooks: Boolean,
        val serverOptions: List<ServerFilterOption>,
        val serverChip: String?,
        val fastScroll: List<LetterIndex>
    )

    private class Device(
        val layout: LibraryLayout,
        val online: Boolean,
        val downloadedOnly: Boolean
    )

    private val content = combine(
        observeBrowseItems(books, downloads, merging),
        books.observeCollections(),
        queryStore.query,
        _view,
        servers.observeAll()
    ) { all, collections, rawQuery, view, serverList ->
        // A deleted server's id never hides books, even before the store drops it.
        val query = pruneServerFilter(rawQuery, serverList.mapTo(mutableSetOf()) { it.id })
        val shown = applyQuery(all, query)
        val items = if (view.isGrouped) emptyList() else shown
        val groups = groupsFor(view, shown, collections)
        Content(
            view = view,
            query = query,
            items = items,
            groups = groups,
            hasBooks = all.isNotEmpty(),
            // Counted from every book, so the numbers do not change with the filters.
            serverOptions = serverFilterOptions(serverList, all, query.serverIds),
            serverChip = serverChipLabel(query.serverIds, serverList),
            fastScroll = fastScrollIndex(fastScrollKeys(view, query.sort, items, groups))
        )
    }.flowOn(dispatcher)

    private val device = combine(settings.libraryLayout, connectivity.isOnline, settings.downloadedOnly) { layout, online, downloadedOnly ->
        Device(layout, online, downloadedOnly)
    }

    private val librariesHidden = combine(books.observeLibraries(), servers.observeAll()) { libraries, serverList ->
        allLibrariesHidden(libraries, serverList)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        content, servers.observeAll(), device, syncCoordinator.state, librariesHidden
    ) { content, serverList, device, sync, hidden ->
        LibraryUiState(
            view = content.view,
            layout = device.layout,
            query = content.query,
            items = content.items,
            groups = content.groups,
            hasBooks = content.hasBooks,
            hasServers = serverList.isNotEmpty(),
            allLibrariesHidden = hidden,
            isLoading = false,
            isSyncing = sync.isSyncing,
            syncError = sync.syncError,
            signInRequiredNames = sync.signInRequiredNames,
            allServersNeedSignIn = sync.allServersNeedSignIn,
            online = device.online,
            downloadedOnly = device.downloadedOnly,
            serverOptions = content.serverOptions,
            serverChip = content.serverChip,
            fastScroll = content.fastScroll
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    // User actions

    fun onViewSelected(view: LibraryView) {
        savedState[VIEW_KEY] = view.name
        _view.value = view
    }

    fun onLayoutSelected(layout: LibraryLayout) {
        viewModelScope.launch { settings.setLibraryLayout(layout) }
    }

    fun onChipSelected(chip: FilterChip) = queryStore.update { queryFor(chip, it) }

    fun onProgressFilterSelected(filter: ProgressFilter) = queryStore.update { it.copy(progress = filter) }

    fun onDownloadFilterSelected(filter: DownloadFilter) = queryStore.update { it.copy(download = filter) }

    fun onSortSelected(sort: BrowseSort) = queryStore.update { it.copy(sort = sort) }

    /** Ticks or unticks one server in the Filter sheet's Server section. */
    fun onServerToggled(serverId: String) {
        val allIds = uiState.value.serverOptions.map { it.id }
        if (serverId !in allIds) return
        queryStore.update { it.copy(serverIds = toggleServer(it.serverIds, serverId, allIds)) }
    }

    /** Shows every server again (the server chip's clear button). */
    fun onServerFilterCleared() = queryStore.update { it.copy(serverIds = emptySet()) }

    /** Back to no filters and the default sort. */
    fun onClearFilters() = queryStore.update { BrowseQuery() }

    fun onRefreshRequested() {
        viewModelScope.launch { syncCoordinator.syncNowAll() }
    }

    fun onErrorDismissed() {
        syncCoordinator.dismissError()
    }

    private companion object {
        const val VIEW_KEY = "library_view"
    }
}
