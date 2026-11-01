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
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.chosenPlaySource
import com.chapterplayer.domain.browse.hasCopy
import com.chapterplayer.domain.browse.serverDisplayNames
import com.chapterplayer.domain.browse.sourceOptions
import com.chapterplayer.ui.details.SourceAction
import com.chapterplayer.ui.details.SourceSheetUi
import com.chapterplayer.ui.details.noCopyText
import com.chapterplayer.ui.details.sourceRow
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
    val serverFilterOn: Boolean = false,
    /** The "Play from" sheet Resume opened for a book on several servers; null when closed. */
    val sourceSheet: SourceSheetUi? = null,
    /** The merging inputs (null without merging), for Resume's choice of copy. */
    val mergeContext: MergeContext? = null,
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
    private val sheetOpen = MutableStateFlow(false)
    // What runs once the copy picked in the sheet has started (opening the player).
    private var afterSheetStart: () -> Unit = {}

    private class Extras(val local: Local, val context: MergeContext?, val sheetOpen: Boolean)

    val uiState: StateFlow<HomeUiState> = combine(
        observeBrowseItems(books, downloads, merging),
        servers.observeAll(),
        queryStore.query,
        combine(connectivity.isOnline, settings.downloadedOnly, syncCoordinator.state) { online, downloadedOnly, sync ->
            Triple(online, downloadedOnly, sync.isSyncing)
        },
        combine(local, merging.context, sheetOpen) { local, context, open -> Extras(local, context, open) }
    ) { items, serverList, rawQuery, (online, downloadedOnly, syncing), extras ->
        val local = extras.local
        val context = extras.context
        val query = pruneServerFilter(rawQuery, serverList.mapTo(mutableSetOf()) { it.id })
        // The card and the series shelf come from every book, so a saved filter never hides them.
        val resume = resumeCandidate(items)
        // The entry's id: the card resumes one copy, but the shelves list the entry.
        val resumeId = resume?.entry?.book?.id
        val entry = resume?.entry?.takeIf { it.isMerged }
        // The sheet marks the copy played last; the position is the same in every copy (progress is mirrored).
        val sheet = if (extras.sheetOpen && entry != null && context != null) {
            val names = serverDisplayNames(serverList)
            SourceSheetUi(
                SourceAction.PLAY,
                sourceOptions(entry, context.onlineServerIds, context.deviceOnline)
                    .map { sourceRow(it, names, lastPlayedId = entry.progress?.bookId) },
            )
        } else null
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
            serverFilterOn = query.serverIds.isNotEmpty(),
            sourceSheet = sheet,
            mergeContext = context,
        )
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Resume on the card for [book]. A book on one server starts at once. A book on several servers follows the Play
     * rule of the details page: a downloaded copy starts at once, else the "Play from" choice's copy when it can play,
     * else the "Play from" sheet asks (with no copy able to play, the error says why). [onStarted] runs when playback
     * began, so the caller can open the player; a failure shows as [HomeUiState.playError] with a safe message.
     */
    fun resume(book: AudioBook, onStarted: () -> Unit = {}) {
        if (local.value.starting) return
        val state = uiState.value
        val entry = state.resume?.entry?.takeIf { it.isMerged && it.hasCopy(book.id) }
        val context = state.mergeContext
        if (entry == null || context == null) return start(book, onStarted)
        val chosen = chosenPlaySource(entry, context.playChoice, context.onlineServerIds)
        if (chosen != null) return start(chosen.book, onStarted)
        val options = sourceOptions(entry, context.onlineServerIds, context.deviceOnline)
        if (options.none { it.playable }) {
            local.value = Local(error = noCopyText(options.firstNotNullOfOrNull { it.unavailableReason }))
            return
        }
        afterSheetStart = onStarted
        sheetOpen.value = true
    }

    /** A row of the "Play from" sheet was chosen: that copy starts where the book was left. */
    fun onSourceChosen(copyId: String) {
        val sheet = uiState.value.sourceSheet ?: return
        val row = sheet.rows.firstOrNull { it.bookId == copyId } ?: return
        if (!row.enabled || local.value.starting) return
        val copy = uiState.value.resume?.entry?.allSources?.firstOrNull { it.book.id == copyId } ?: return
        sheetOpen.value = false
        start(copy.book, afterSheetStart)
    }

    fun onSourceSheetDismissed() {
        sheetOpen.value = false
    }

    private fun start(book: AudioBook, onStarted: () -> Unit) {
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
