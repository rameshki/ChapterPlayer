package com.chapterplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.diagnostics.DiagnosticsData
import com.chapterplayer.data.diagnostics.DiagnosticsLogger
import com.chapterplayer.data.diagnostics.diagnosticsFailureText
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.searchItems
import com.chapterplayer.domain.browse.serverKindName
import com.chapterplayer.domain.diagnostics.DiagCopy
import com.chapterplayer.domain.diagnostics.DiagDownload
import com.chapterplayer.domain.diagnostics.DiagnosticsInput
import com.chapterplayer.domain.diagnostics.ServerReadState
import com.chapterplayer.domain.diagnostics.buildReport
import com.chapterplayer.domain.diagnostics.describeMerge
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.ui.browse.observeBrowseItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** One line of the book picker. [key] is the id of the entry's first copy. */
data class DiagnosticsBookChoice(val key: String, val title: String, val detail: String)

/**
 * @param report the finished, redacted report of the chosen book (what the page shows, copies and logs); null with no book chosen.
 * @param notice one line for the user (offline, nothing to read, a table that could not be read).
 */
data class DiagnosticsUiState(
    val query: String = "",
    val choices: List<DiagnosticsBookChoice> = emptyList(),
    val selectedKey: String? = null,
    val report: String? = null,
    val reading: Boolean = false,
    val online: Boolean = true,
    val notice: String? = null,
)

/**
 * The Diagnostics page (debug builds only): picks a book, reads what the app and each copy's server hold, and builds
 * the redacted report. It changes no data of the app and no server state: the tables are only read, the server reads
 * are plain GETs of the progress the app also reads, and they use the stored token as it is, so they never refresh
 * (rotate) a token or sign a server out; a refused token is just reported.
 */
class DiagnosticsViewModel(
    private val books: BookRepository,
    downloads: DownloadStates,
    merging: SourceMerging,
    servers: ServerRepository,
    private val data: DiagnosticsData,
    private val logger: DiagnosticsLogger,
    private val connectivity: ConnectivityMonitor,
    private val dispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private class Base(
        val items: List<BrowseItem> = emptyList(),
        val context: MergeContext? = null,
        val serverNames: Map<String, String> = emptyMap(),
    )

    private data class Extras(val pending: List<PendingProgress>, val download: DiagDownload?)

    private data class Session(
        val selectedKey: String? = null,
        val reads: Map<String, ServerReadState> = emptyMap(),
        val extras: Map<String, Extras> = emptyMap(),
        val reading: Boolean = false,
        val notice: String? = null,
    )

    /** What the report is made from; the report is rebuilt only when this changes (not on other books' progress ticks). */
    private data class ReportInputs(
        val entry: BrowseItem?,
        val context: MergeContext?,
        val names: Map<String, String>,
        val session: Session,
    )

    private val query = MutableStateFlow("")
    private val session = MutableStateFlow(Session())

    private val base: StateFlow<Base> = combine(
        observeBrowseItems(books, downloads, merging), merging.context, servers.observeAll()
    ) { items, context, serverList ->
        Base(items, context, serverList.associate { it.id to it.name })
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, Base())

    private val reportFlow = combine(base, session) { b, s ->
        ReportInputs(entryOf(b, s.selectedKey), b.context, b.serverNames, s)
    }.distinctUntilChanged().map { inputs -> inputs.entry?.let { reportFor(inputs.names, inputs.context, it, inputs.session) } }

    private val choicesFlow = combine(base, query, session) { b, text, s ->
        if (s.selectedKey != null) emptyList() else choicesFor(b, text)
    }.distinctUntilChanged()

    val uiState: StateFlow<DiagnosticsUiState> = combine(reportFlow, choicesFlow, query, session, connectivity.isOnline) { report, choices, text, s, online ->
        DiagnosticsUiState(
            query = text,
            choices = choices,
            selectedKey = s.selectedKey,
            report = report,
            reading = s.reading,
            online = online,
            notice = s.notice,
        )
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    /** Chooses the book with the entry whose first copy has [key]; the local rows are read at once, the servers only on request. */
    fun select(key: String) {
        session.value = Session(selectedKey = key)
        viewModelScope.launch(dispatcher) { reloadExtras(key) }
    }

    /** Back to the picker. */
    fun clearSelection() {
        session.value = Session()
    }

    /** Reads the queue and the download rows again (they change while the book plays or downloads). */
    fun refreshLocal() {
        val key = session.value.selectedKey ?: return
        viewModelScope.launch(dispatcher) { reloadExtras(key) }
    }

    /**
     * Asks each copy's server for its progress (plain reads with the stored token; nothing is refreshed or signed out),
     * then writes the report of that book to the log. Online only; a failure becomes one safe sentence in the report.
     * A second tap while a read runs does nothing, and a read of a book that is no longer chosen is dropped.
     */
    fun readFromServer() {
        val key = session.value.selectedKey ?: return
        val entry = entryOf(base.value, key) ?: return
        // Taken synchronously, so a double tap starts one read.
        var started = false
        session.update { s ->
            started = !s.reading
            if (started) s.copy(reading = true, notice = null) else s
        }
        if (!started) return
        viewModelScope.launch(dispatcher) {
            var reads = emptyMap<String, ServerReadState>()
            try {
                if (!connectivity.isOnline.first()) {
                    session.update { if (it.selectedKey == key) it.copy(notice = "The phone is offline, so the servers cannot be read.") else it }
                    return@launch
                }
                for (member in entry.allSources) {
                    val state = try {
                        books.readServerProgress(member.book.id).fold(
                            onSuccess = { ServerReadState.Read(it, nowMs()) },
                            onFailure = { ServerReadState.Failed(diagnosticsFailureText(it)) },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ServerReadState.Failed(diagnosticsFailureText(e))
                    }
                    reads = reads + (member.book.id to state)
                    session.update { if (it.selectedKey == key) it.copy(reads = it.reads + (member.book.id to state)) else it }
                }
                val extras = loadExtras(entry)
                session.update { if (it.selectedKey == key) it.copy(extras = extras.first, notice = extras.second ?: it.notice) else it }
                // The report of the book that was read, even if another one is chosen by now.
                val b = base.value
                logger.log(reportFor(b.serverNames, b.context, entryOf(b, key) ?: entry, Session(key, reads, extras.first)))
            } finally {
                session.update { if (it.selectedKey == key) it.copy(reading = false) else it }
            }
        }
    }

    /** Writes the current report to the log (`adb logcat -s ChapterPlayerDiag`). */
    fun logReport() {
        val b = base.value
        val s = session.value
        val entry = entryOf(b, s.selectedKey) ?: return
        logger.log(reportFor(b.serverNames, b.context, entry, s))
    }

    /** The rows of the phone's tables for the copies of [entry], and a notice when a table could not be read. */
    private suspend fun loadExtras(entry: BrowseItem): Pair<Map<String, Extras>, String?> {
        val out = LinkedHashMap<String, Extras>()
        var notice: String? = null
        for (member in entry.allSources) {
            try {
                out[member.book.id] = Extras(data.pending(member.book.id), data.download(member.book.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "The phone's tables could not be read (${e::class.simpleName ?: "error"})."
            }
        }
        return out to notice
    }

    private suspend fun reloadExtras(key: String) {
        val entry = entryOf(base.value, key) ?: return
        val (extras, notice) = loadExtras(entry)
        session.update { if (it.selectedKey == key) it.copy(extras = extras, notice = notice ?: it.notice) else it }
    }

    private fun entryOf(b: Base, key: String?): BrowseItem? =
        if (key == null) null else b.items.firstOrNull { item -> item.allSources.any { it.book.id == key } }

    private fun choicesFor(b: Base, text: String): List<DiagnosticsBookChoice> {
        val matches = if (text.isBlank()) b.items.sortedBy { it.book.title.lowercase() } else searchItems(b.items, text)
        return matches.take(MAX_CHOICES).map { item ->
            DiagnosticsBookChoice(
                key = item.allSources.first().book.id,
                title = item.book.title,
                detail = item.allSources.joinToString(", ") { "${b.serverNames[it.book.serverId] ?: "unnamed server"} (${labelOf(it.book.source)})" },
            )
        }
    }

    private fun reportFor(names: Map<String, String>, context: MergeContext?, entry: BrowseItem, s: Session): String {
        val copies = entry.allSources.map { member ->
            val extras = s.extras[member.book.id]
            DiagCopy(
                bookId = member.book.id,
                serverName = names[member.book.serverId] ?: "unnamed server",
                sourceLabel = labelOf(member.book.source),
                isPlex = member.book.source == ServerSource.PLEX,
                local = member.progress,
                server = s.reads[member.book.id] ?: ServerReadState.NotRead,
                pending = extras?.pending.orEmpty(),
                download = extras?.download,
            )
        }
        val merge = context?.let { describeMerge(entry, it, names) }
        return buildReport(DiagnosticsInput(entry.book.title, entry.book.author, copies, merge, nowMs()))
    }

    private fun labelOf(source: ServerSource) = serverKindName(source)

    companion object {
        const val MAX_CHOICES = 30
    }
}
