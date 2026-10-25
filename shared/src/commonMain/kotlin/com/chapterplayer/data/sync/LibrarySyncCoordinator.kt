package com.chapterplayer.data.sync

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private const val ERROR_SEPARATOR = "\n"

private const val SERVER_LIST_ERROR = "Could not read the server list"

private const val SIGN_IN_AGAIN_TEXT = "Sign in again in Settings"

data class SyncUiState(
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    /** Display names of servers whose sign-in is gone (their stored token is blank). */
    val signInRequiredNames: List<String> = emptyList(),
    val allServersNeedSignIn: Boolean = false
)

/** What the Libraries check-list needs from the sync: ask for a sync of one server and watch its state. */
interface LibrarySync {
    val state: StateFlow<SyncUiState>
    fun requestSync(serverId: String)
}

/**
 * Syncs the servers' books into the cache for the whole app. It lives as long as the app, so a
 * fresh install or a newly added server fills the library whichever screen is open.
 */
class LibrarySyncCoordinator(
    private val books: BookRepository,
    private val servers: ServerRepository,
    private val scope: CoroutineScope,
    /** Chooses Plex addresses: before a first sync, and again after a sync that could not connect. Null for none. */
    private val connections: PlexConnectionManager? = null,
    /** Keeps each server's last successful sync and last error across restarts. */
    private val statusStore: ServerStatusStore = NoServerStatusStore,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Reads Plex album lengths after a Plex sync, in the background; null for none. */
    private val lengthFiller: PlexLengthFiller? = null,
) : LibrarySync {
    private data class Activity(val running: Int = 0, val errors: List<String> = emptyList())

    private val activity = MutableStateFlow(Activity())
    private val lock = Mutex()
    private val inFlight = mutableSetOf<String>()
    private val queued = mutableSetOf<String>()
    private var started = false

    /** The server list, with a failing upstream flow turned into an error message instead of a crash of the app scope. */
    private fun safeServers(onFailure: suspend FlowCollector<List<ServerConfig>>.() -> Unit = {}) =
        servers.observeAll().catch { e ->
            if (e is CancellationException) throw e
            addErrors(listOf(SERVER_LIST_ERROR))
            onFailure()
        }

    /** The servers syncing right now (each runs at most once at a time). */
    private val syncingIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Each configured server's sync and connection, keyed by server id, for the Servers page: syncing, the last success
     * with the cached book count, or the last failure; the connection in use and, for Plex, what the last probe found.
     */
    val serverStatus: StateFlow<Map<String, ServerSyncStatus>> = combine(
        servers.observeAll().catch { e -> if (e is CancellationException) throw e else emit(emptyList()) },
        statusStore.all.catch { e -> if (e is CancellationException) throw e else emit(emptyMap()) },
        books.observeAllBooks()
            .map { list -> list.groupingBy { it.serverId }.eachCount() }
            .catch { e -> if (e is CancellationException) throw e else emit(emptyMap()) },
        syncingIds,
        lengthFiller?.progress ?: flowOf(emptyMap())
    ) { list, records, counts, syncing, lengths ->
        list.associate { server ->
            val status = serverSyncStatus(server, records[server.id] ?: ServerStatusRecord(), counts[server.id] ?: 0, server.id in syncing)
            server.id to status.copy(lengthsNote = lengths[server.id]?.let { lengthsNote(it) })
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** "Test connection" on the Servers page: every Plex connection tried at once; null for a server that has none. */
    suspend fun testConnection(serverId: String): ConnectionTest? = connections?.testConnection(serverId)

    override val state: StateFlow<SyncUiState> = combine(safeServers { emit(emptyList()) }, activity) { serverList, act ->
        SyncUiState(
            isSyncing = act.running > 0,
            syncError = act.errors.takeIf { it.isNotEmpty() }?.joinToString(ERROR_SEPARATOR),
            signInRequiredNames = serverList.filter { it.needsSignIn }.map { it.name },
            allServersNeedSignIn = serverList.isNotEmpty() && serverList.all { it.needsSignIn }
        )
    }.stateIn(scope, SharingStarted.Eagerly, SyncUiState())

    /** Starts the automatic sync of servers that have nothing cached yet. Safe to call again. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            safeServers()
                .distinctUntilChangedBy { list -> list.map { it.id }.toSet() }
                .collect { list ->
                    // An unexpected failure here must not end the collector, or later servers would never sync.
                    try {
                        val cachedServerIds = books.observeAllBooks().first().mapTo(mutableSetOf()) { it.serverId }
                        val uncached = list.filter { it.id !in cachedServerIds }
                        if (uncached.isNotEmpty()) syncServers(uncached)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        addErrors(listOf("Sync failed: ${e.message ?: "Unknown error"}"))
                    }
                }
        }
    }

    /**
     * Syncs every configured server now (the Library tab's refresh button and pull to refresh). The user asked, so
     * earlier messages go first and a Plex connection failure re-probes at once (no 30 s wait). Still one sync per server
     * at a time: a server that is syncing gets one follow-up run.
     */
    suspend fun syncNowAll() {
        dismissError()
        try {
            syncServers(servers.observeAll().first(), queueBusy = true, force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addErrors(listOf("Sync failed: ${e.message ?: "Unknown error"}"))
        }
    }

    /** Syncs one server now, as [syncNowAll] does for all ("Sync now" on the Servers page). An unknown id does nothing. */
    suspend fun syncNow(serverId: String) {
        try {
            val server = servers.getById(serverId) ?: return
            syncServers(listOf(server), queueBusy = true, force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addErrors(listOf("Sync failed: ${e.message ?: "Unknown error"}"))
        }
    }

    fun dismissError() {
        activity.update { it.copy(errors = emptyList()) }
    }

    /** Messages of overlapping runs add up; a repeat of one already shown is not listed twice. */
    private fun addErrors(added: List<String>) {
        activity.update { it.copy(errors = (it.errors + added).distinct()) }
    }

    /**
     * Syncs one server soon, for a change that must not be lost (a library turned on or off). If that server
     * is syncing, ONE follow-up run starts right after it, however many requests arrive meanwhile; the
     * follow-up reads the server again, so it sees the change. Otherwise a sync starts at once.
     */
    override fun requestSync(serverId: String) {
        scope.launch {
            try {
                val claimed = lock.withLock {
                    if (serverId in inFlight) {
                        queued.add(serverId)
                        false
                    } else {
                        inFlight.add(serverId)
                        true
                    }
                }
                if (!claimed) return@launch
                val server = try {
                    servers.getById(serverId)
                } catch (t: Throwable) {
                    release(listOf(serverId))
                    throw t
                }
                if (server == null) {
                    release(listOf(serverId))
                    return@launch
                }
                runClaimed(listOf(server))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addErrors(listOf("Sync failed: ${e.message ?: "Unknown error"}"))
            }
        }
    }

    /**
     * A server that is already syncing is left to that run, so two syncs of one server never overlap.
     * With [queueBusy] (the user asked) such a server gets ONE follow-up run, like [requestSync], not a dropped request.
     */
    private suspend fun syncServers(candidates: List<ServerConfig>, queueBusy: Boolean = false, force: Boolean = false) {
        val mine = lock.withLock {
            candidates.filter { server ->
                val claimed = inFlight.add(server.id)
                if (!claimed && queueBusy) queued.add(server.id)
                claimed
            }
        }
        runClaimed(mine, force)
    }

    private suspend fun release(ids: List<String>) {
        withContext(NonCancellable) {
            lock.withLock {
                inFlight.removeAll(ids.toSet())
                queued.removeAll(ids.toSet())
            }
        }
    }

    /** Runs the claimed servers, then any follow-up that was requested meanwhile, until none is queued. */
    private suspend fun runClaimed(claimed: List<ServerConfig>, force: Boolean = false) {
        if (claimed.isEmpty()) return
        // One "running" count for the whole chain of runs, so isSyncing does not flicker between a run and its follow-up.
        activity.update { it.copy(running = it.running + 1) }
        try {
            var current = claimed
            while (current.isNotEmpty()) {
                var again = emptyList<String>()
                var orphaned = emptyList<String>()
                val errors = mutableListOf<String>()
                var completed = false
                try {
                    // Only the run the user asked for is forced; a follow-up is an ordinary run.
                    val forced = force && current === claimed
                    current.forEach { server -> syncOne(server, errors, forced) }
                    completed = true
                } finally {
                    withContext(NonCancellable) {
                        lock.withLock {
                            val ids = current.map { it.id }
                            val wanted = ids.filter { queued.remove(it) }
                            if (completed) again = wanted else orphaned = wanted
                            inFlight.removeAll((ids - again.toSet()).toSet())
                        }
                        activity.update { it.copy(errors = (it.errors + errors).distinct()) }
                    }
                    // A cancelled run cannot continue, but a change asked for meanwhile must still be synced:
                    // hand it to the app scope, which outlives the cancelled caller.
                    orphaned.forEach { requestSync(it) }
                }
                // The follow-up reads each server again, so it sees changes made while the last run was going.
                val fresh = try {
                    again.mapNotNull { servers.getById(it) }
                } catch (t: Throwable) {
                    release(again)
                    // Cancelled while looking up: the requested syncs must still happen, on the app scope.
                    if (t is CancellationException) again.forEach { requestSync(it) }
                    throw t
                }
                release(again - fresh.map { it.id }.toSet())
                current = fresh
            }
        } finally {
            withContext(NonCancellable) { activity.update { it.copy(running = it.running - 1) } }
        }
    }

    private suspend fun syncOne(requested: ServerConfig, errors: MutableList<String>, force: Boolean = false) {
        if (requested.needsSignIn) {
            // The stored sign-in is gone, so there is nothing to send to the server.
            errors.add(signInAgainMessage(requested))
            recordOutcome(requested.id, SIGN_IN_AGAIN_TEXT)
            return
        }
        syncingIds.update { it + requested.id }
        try {
            syncReachable(requested, errors, force)
        } finally {
            syncingIds.update { it - requested.id }
        }
    }

    private suspend fun syncReachable(requested: ServerConfig, errors: MutableList<String>, force: Boolean) {
        var server = choose { connections?.beforeSync(requested) } ?: requested
        var outcome = syncBooks(server)
        // A Plex address that cannot be reached: find the one that answers now, and retry once on it.
        var connectionMessage: String? = null
        val failure = outcome.exceptionOrNull()
        if (failure != null && failure !is CancellationException && connections != null) {
            val next = choose { connections.afterFailure(server, failure, force) }
            if (next?.retryWith != null) {
                server = next.retryWith
                outcome = syncBooks(server)
            } else {
                connectionMessage = next?.message
            }
        }
        // The books are cached: bring in what the server knows about listening to them (best effort).
        if (outcome.isSuccess && server.source == ServerSource.ABS) {
            mergeProgress(server)
            pullBookmarks(server)
        }
        // Plex album lists carry no length: read the lengths afterwards, without holding up this sync.
        if (outcome.isSuccess && server.source == ServerSource.PLEX) lengthFiller?.request(server)
        val reason = outcome.exceptionOrNull()?.let { throwable ->
            if (throwable is CancellationException) throw throwable
            if (throwable is SignInRequiredException) SIGN_IN_AGAIN_TEXT else connectionMessage ?: syncFailureReason(throwable, server.baseUrl)
        }
        if (reason != null) errors.add("${server.name}: $reason")
        recordOutcome(server.id, reason)
        dropIfDeleted(server)
    }

    /** Saves when the server last synced, or why it failed ([failure]); best effort, the sync itself is done. */
    private suspend fun recordOutcome(serverId: String, failure: String?) {
        try {
            val now = nowMs()
            statusStore.update(serverId) {
                // A success also ends a failed probe's note ("Could not reach the server"): it reached it after all.
                if (failure == null) it.copy(
                    lastSuccessAtMs = now, lastErrorText = null, lastErrorAtMs = null,
                    probeNote = if (it.probeFailed) null else it.probeNote,
                    probeFailures = if (it.probeFailed) emptyList() else it.probeFailures,
                    probeFailed = false,
                )
                else it.copy(lastErrorText = failure, lastErrorAtMs = now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The page shows the older state until the next sync.
        }
    }

    /** A repository that throws instead of returning a failure must not crash the app. */
    private suspend fun syncBooks(server: ServerConfig): Result<Unit> = try {
        books.syncBooksFromServer(server)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** The connection choice is best effort: a failure there leaves the sync as it was. */
    private suspend fun <T> choose(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** A failure here never fails the sync and shows no error: the next sync tries again. */
    private suspend fun mergeProgress(server: ServerConfig) {
        try {
            books.mergeAllServerProgress(server.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /** Bookmarks of the cached books; best effort like the progress merge (a failed result or a throw changes nothing here). */
    private suspend fun pullBookmarks(server: ServerConfig) {
        try {
            books.pullBookmarks(server.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /**
     * The user may delete a server while its sync runs; the sync then writes the books of a server that is
     * gone. Deleting again is safe (it is idempotent) and removes those rows, so they do not come back.
     */
    private suspend fun dropIfDeleted(server: ServerConfig) {
        try {
            if (servers.getById(server.id) == null) servers.delete(server.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next sync or delete heals it; the sync itself already finished.
        }
    }

    private fun signInAgainMessage(server: ServerConfig) = "${server.name}: $SIGN_IN_AGAIN_TEXT"
}
