package com.chapterplayer.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.chipFor
import com.chapterplayer.domain.browse.applyQuery
import com.chapterplayer.domain.browse.searchItems
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

enum class SearchStatus { PROMPT, NO_RESULTS, FILTERED_OUT, RESULTS }

/** @param query the text the results were made for (it trails the text field by the debounce). */
data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.PROMPT,
    val results: List<BrowseItem> = emptyList(),
    /** The Downloaded filter is on (and no other), so a filtered-out result means nothing matching is on the phone. */
    val downloadedFilterOn: Boolean = false,
    val online: Boolean = true,
    val downloadedOnly: Boolean = false,
    /** The Library's server filter hides some results; Search says so and offers to clear it. */
    val serverFilterOn: Boolean = false
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    books: BookRepository,
    downloads: DownloadStates,
    private val queryStore: BrowseQueryStore,
    connectivity: ConnectivityMonitor,
    settings: AppSettings,
    dispatcher: CoroutineDispatcher,
    merging: SourceMerging = SourceMerging.None
) : ViewModel() {

    private val _queryText = MutableStateFlow("")

    /** What the text field shows; kept here so it survives tab switches and rotation. */
    val queryText: StateFlow<String> = _queryText

    fun onQueryChange(text: String) {
        _queryText.value = text
    }

    // Clearing the field settles at once; typing waits for a pause.
    private val settledQuery = _queryText.debounce { text -> if (text.isBlank()) 0L else DEBOUNCE_MS }

    val uiState: StateFlow<SearchUiState> = combine(
        settledQuery,
        observeBrowseItems(books, downloads, merging),
        queryStore.query,
        connectivity.isOnline,
        settings.downloadedOnly
    ) { text, items, query, online, downloadedOnly ->
        val matches = searchItems(items, text)
        val shown = applyQuery(matches, query)
        SearchUiState(
            query = text,
            status = when {
                text.isBlank() -> SearchStatus.PROMPT
                matches.isEmpty() -> SearchStatus.NO_RESULTS
                shown.isEmpty() -> SearchStatus.FILTERED_OUT
                else -> SearchStatus.RESULTS
            },
            results = shown,
            downloadedFilterOn = chipFor(query) == FilterChip.DOWNLOADED && query.serverIds.isEmpty(),
            online = online,
            downloadedOnly = downloadedOnly,
            serverFilterOn = query.serverIds.isNotEmpty()
        )
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    /** Shows every server's books again (the hint's button). */
    fun onServerFilterCleared() = queryStore.update { it.copy(serverIds = emptySet()) }

    companion object {
        const val DEBOUNCE_MS = 200L
    }
}
