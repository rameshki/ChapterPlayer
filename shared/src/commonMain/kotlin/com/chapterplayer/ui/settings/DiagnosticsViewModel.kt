package com.chapterplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.diagnostics.DiagnosticsData
import com.chapterplayer.data.diagnostics.DiagnosticsLogger
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.syncFailureReason
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.searchItems
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** One line of the book picker. [key] is the id of the entry's first copy. */
data class DiagnosticsBookChoice(val key: String, val title: String, val detail: String)

/**
 * @param report the finished, redacted report of the chosen book (what the page shows, copies and logs); null with no book chosen.
 * @param notice one line for the user (offline, nothing to read).
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
 * The Diagnostics page (debug builds): picks a book, reads what the app and each copy's server hold, and builds the
 * redacted report. Read only: nothing here changes data, and the only network calls are the progress reads the app
 * already makes when it opens a book.
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

    private class Extras(val pending: List<PendingProgress>, val download: DiagDownload?)

    private data class Session(
        val selectedKey: String? = null,
        val reads: Map<String, ServerReadState> = emptyMap(),
        val extras: Map<String, Extras> = emptyMap(),
        val reading: Boolean = false,
        val notice: String? = null,
    )

    private val query = MutableStateFlow("")
    private val session = MutableStateFlow(Session())

    private val base: StateFlow<Base> = combine(
        observeBrowseItems(books, downloads, merging), merging.context, servers.observeAll()
    ) { items, context, serverList ->
        Base(items, context, serverList.associate { it.id to it.name })
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, Base())

    val uiState: StateFlow<DiagnosticsUiState> = combine(base, query, session, connectivity.isOnline) { b, text, s, online ->
        val entry = entryOf(b, s.selectedKey)
        DiagnosticsUiState(
            query = text,
            choices = choicesFor(b, text),
            selectedKey = s.selectedKey,
            report = entry?.let { reportFor(b, it, s) },
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
        viewModelScope.launch(dispatcher) { reloadExtras() }
    }

    /** Back to the picker. */
    fun clearSelection() {
        session.value = Session()
    }

    /** Reads the queue and the download rows again (they change while the book plays or downloads). */
    fun refreshLocal() {
        viewModelScope.launch(dispatcher) { reloadExtras() }
    }

    /**
     * Asks each copy's server for its progress, the same reads the app makes when it opens a book, then writes the report
     * to the log. Online only; a failure becomes one safe line in the report.
     */
    fun readFromServer() {
        val s = session.value
        val entry = entryOf(base.value, s.selectedKey) ?: return
        if (s.reading) return
        viewModelScope.launch(dispatcher) {
            if (!connectivity.isOnline.first()) {
                session.update { it.copy(notice = "The phone is offline, so the servers cannot be read.") }
                return@launch
            }
            session.update { it.copy(reading = true, notice = null) }
            for (member in entry.allSources) {
                val state = try {
                    books.readServerProgress(member.book.id).fold(
                        onSuccess = { ServerReadState.Read(it, nowMs()) },
                        onFailure = { ServerReadState.Failed(syncFailureReason(it)) },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServerReadState.Failed(syncFailureReason(e))
                }
                session.update { it.copy(reads = it.reads + (member.book.id to state)) }
            }
            reloadExtras()
            session.update { it.copy(reading = false) }
            logReport()
        }
    }

    /** Writes the current report to the log (`adb logcat -s ChapterPlayerDiag`). */
    fun logReport() {
        val b = base.value
        val s = session.value
        val entry = entryOf(b, s.selectedKey) ?: return
        logger.log(reportFor(b, entry, s))
    }

    private suspend fun reloadExtras() {
        val entry = entryOf(base.value, session.value.selectedKey) ?: return
        val extras = entry.allSources.associate { member ->
            member.book.id to Extras(data.pending(member.book.id), data.download(member.book.id))
        }
        session.update { it.copy(extras = extras) }
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

    private fun reportFor(b: Base, entry: BrowseItem, s: Session): String {
        val copies = entry.allSources.map { member ->
            val extras = s.extras[member.book.id]
            DiagCopy(
                bookId = member.book.id,
                serverName = b.serverNames[member.book.serverId] ?: "unnamed server",
                sourceLabel = labelOf(member.book.source),
                isPlex = member.book.source == ServerSource.PLEX,
                local = member.progress,
                server = s.reads[member.book.id] ?: ServerReadState.NotRead,
                pending = extras?.pending.orEmpty(),
                download = extras?.download,
            )
        }
        val merge = b.context?.let { describeMerge(entry, it, b.serverNames) }
        return buildReport(DiagnosticsInput(entry.book.title, entry.book.author, copies, merge, nowMs()))
    }

    private fun labelOf(source: ServerSource) = if (source == ServerSource.PLEX) "Plex" else "Audiobookshelf"

    companion object {
        const val MAX_CHOICES = 30
    }
}
