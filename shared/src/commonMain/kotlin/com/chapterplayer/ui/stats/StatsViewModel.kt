package com.chapterplayer.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.stats.ListeningClock
import com.chapterplayer.data.stats.ListeningImport
import com.chapterplayer.data.stats.ListeningLog
import com.chapterplayer.domain.stats.DayZone
import com.chapterplayer.domain.stats.StatsInput
import com.chapterplayer.domain.stats.StatsPeriod
import com.chapterplayer.domain.stats.StatsReport
import com.chapterplayer.domain.stats.TopKind
import com.chapterplayer.domain.stats.buildStats
import com.chapterplayer.domain.stats.epochDayOf
import com.chapterplayer.domain.stats.importOutcomeText
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @param report null until the first figures are worked out.
 * @param message a line to show once (the outcome of Refresh); cleared with [StatsViewModel.onMessageShown].
 */
data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val report: StatsReport? = null,
    val topKind: TopKind = TopKind.AUTHORS,
    val refreshing: Boolean = false,
    val message: String? = null,
)

/** The statistics page: the listening log, the imported history and the library, worked out for the chosen period. */
class StatsViewModel(
    books: BookRepository,
    downloads: DownloadStates,
    overview: DownloadOverview,
    log: ListeningLog,
    servers: ServerRepository,
    private val importer: ListeningImport,
    private val zone: DayZone,
    private val clock: ListeningClock,
    dispatcher: CoroutineDispatcher,
    merging: SourceMerging = SourceMerging.None,
) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.MONTH)
    private val topKind = MutableStateFlow(TopKind.AUTHORS)
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val serverIds = servers.observeAll().map { list -> list.mapTo(HashSet()) { it.id } }

    // History imported from a server that has since been removed no longer counts.
    private val imported = combine(log.observeImportedDays(), log.observeImportedItems(), serverIds) { days, items, ids ->
        days.filter { it.serverId in ids } to items.filter { it.serverId in ids }
    }

    private val input = combine(
        observeBrowseItems(books, downloads, merging), log.observeLog(), imported, overview.storageUsedBytes()
    ) { items, entries, (days, importedItems), bytes ->
        StatsInput(entries, days, importedItems, items, bytes, epochDayOf(clock.nowMs(), zone), zone)
    }

    private val report = combine(input, period) { input, period -> buildStats(input, period) }.flowOn(dispatcher)

    val uiState: StateFlow<StatsUiState> = combine(report, period, topKind, refreshing, message) { report, period, kind, busy, text ->
        StatsUiState(period, report, kind, busy, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun onPeriodSelected(value: StatsPeriod) {
        period.value = value
    }

    fun onTopKindSelected(value: TopKind) {
        topKind.value = value
    }

    /** Reads every Audiobookshelf server's listening history again. */
    fun onRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                message.value = importOutcomeText(importer.refresh())
            } finally {
                refreshing.value = false
            }
        }
    }

    fun onMessageShown() {
        message.value = null
    }
}
