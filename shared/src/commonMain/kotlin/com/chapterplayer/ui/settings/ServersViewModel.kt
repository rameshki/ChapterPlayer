package com.chapterplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.sync.ConnectionTest
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.data.sync.ServerSyncPhase
import com.chapterplayer.data.sync.ServerSyncStatus
import com.chapterplayer.domain.settings.serverStatusLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** One server on the Servers page. Holds no token. */
data class ServerRowUi(
    val id: String,
    val name: String,
    /** "Audiobookshelf" or "Plex". */
    val typeLabel: String,
    /** The connection in use: "Local", "Local address", "Remote", "Relay", "Audiobookshelf address". */
    val connectionLabel: String,
    /** "Last synced 12 min ago · 342 books", "Syncing…" or "Sync failed: <reason>". */
    val statusLine: String,
    val failed: Boolean,
    val syncing: Boolean,
    /** What the last connection check found worth saying (Plex), e.g. that the network blocks plex.direct names. */
    val connectionNote: String?,
    val canTestConnection: Boolean,
    val needsSignIn: Boolean,
    val libraryId: String?,
)

/** One connection in the "Test connection" dialog. */
data class ConnectionTestRowUi(val kindLabel: String, val address: String, val ok: Boolean, val result: String, val inUse: Boolean)

/** The "Test connection" dialog: [running] while the connections are being tried. */
data class ConnectionTestUi(
    val serverName: String,
    val running: Boolean = false,
    val rows: List<ConnectionTestRowUi> = emptyList(),
    val note: String? = null,
    /** Shown instead of rows when the test could not run. */
    val message: String? = null,
)

/** The rows of the Servers page, in the servers' order. A server with no status yet reads as not synced. */
fun serverRows(servers: List<ServerConfig>, statuses: Map<String, ServerSyncStatus>, nowMs: Long): List<ServerRowUi> =
    servers.map { server ->
        val status = statuses[server.id]
        val phase = status?.phase ?: ServerSyncPhase.Idle(null, 0)
        ServerRowUi(
            id = server.id,
            name = server.name,
            typeLabel = if (server.source == ServerSource.PLEX) "Plex" else "Audiobookshelf",
            connectionLabel = status?.connectionLabel.orEmpty(),
            statusLine = serverStatusLine(phase, nowMs),
            failed = phase is ServerSyncPhase.Failed,
            syncing = phase is ServerSyncPhase.Syncing,
            connectionNote = status?.connectionNote,
            canTestConnection = server.source == ServerSource.PLEX,
            needsSignIn = server.needsSignIn,
            libraryId = server.libraryId,
        )
    }

/** The dialog for a finished test; a null [test] (not a signed-in Plex server) gives a message instead. */
fun connectionTestUi(serverName: String, test: ConnectionTest?): ConnectionTestUi {
    if (test == null) return ConnectionTestUi(serverName, message = "Could not test this server")
    return ConnectionTestUi(
        serverName = serverName,
        rows = test.results.map { r ->
            ConnectionTestRowUi(
                kindLabel = r.candidate.kind.label,
                address = r.candidate.uri,
                ok = r.problem == null,
                result = r.problem?.text ?: "OK",
                inUse = r.candidate == test.chosen,
            )
        },
        note = test.note,
        message = if (test.results.isEmpty()) "No connection to try" else null,
    )
}

/**
 * The Servers page: each server's sync and connection, "Sync now", "Test connection" (Plex) and delete. A sync started
 * here runs in [appScope], so leaving the page does not cut it short.
 */
class ServersViewModel(
    private val servers: ServerRepository,
    private val sync: LibrarySyncCoordinator,
    private val appScope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    /** The current time every 30 s, so "12 min ago" moves on while the page is open. */
    private val ticker = flow {
        while (true) {
            emit(nowMs())
            delay(TICK_MS)
        }
    }

    val rows: StateFlow<List<ServerRowUi>> = combine(
        servers.observeAll().catch { e -> if (e is CancellationException) throw e else emit(emptyList()) },
        sync.serverStatus,
        ticker
    ) { list, statuses, now -> serverRows(list, statuses, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _connectionTest = MutableStateFlow<ConnectionTestUi?>(null)
    val connectionTest: StateFlow<ConnectionTestUi?> = _connectionTest.asStateFlow()

    fun syncNow(serverId: String) {
        appScope.launch { sync.syncNow(serverId) }
    }

    private var testJob: Job? = null

    /** Starts a test; one at a time, so a test still running is cancelled and its result never shows. */
    fun testConnection(serverId: String) {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            val name = try {
                servers.getById(serverId)?.name
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: return@launch
            _connectionTest.value = ConnectionTestUi(name, running = true)
            val test = try {
                sync.testConnection(serverId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            _connectionTest.value = connectionTestUi(name, test)
        }
    }

    fun dismissConnectionTest() {
        testJob?.cancel()
        testJob = null
        _connectionTest.value = null
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            try {
                servers.delete(serverId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The row stays visible so the user can try again.
            }
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
