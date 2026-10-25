package com.chapterplayer.data.sync

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.plex.ConnectionPick
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.domain.plex.ProbeFailure
import com.chapterplayer.domain.plex.ProbeResult
import com.chapterplayer.domain.plex.REPROBE_INTERVAL_MS
import com.chapterplayer.domain.plex.connectionNote
import com.chapterplayer.domain.plex.isConnectionFailure
import com.chapterplayer.domain.plex.pickConnection
import com.chapterplayer.domain.plex.probeAll
import com.chapterplayer.domain.plex.reprobeAllowed
import com.chapterplayer.domain.plex.winner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Tries one Plex address with the server's token (in a header); null when the server answered. Never throws. */
fun interface PlexConnectionProbe {
    suspend fun probe(uri: String, token: String): ConnectionProblem?
}

/**
 * The server's connections as plex.tv lists them now, ranked, or null when plex.tv cannot say (no answer, or no server
 * on the account has the server's address). For servers saved before the app kept the list, and for "Test connection".
 */
fun interface PlexCandidateLookup {
    suspend fun candidatesFor(server: ServerConfig): List<PlexCandidate>?
}

/** What to do after a failed sync: sync again with [retryWith] (the address changed), or show [message]. */
data class FailureOutcome(val retryWith: ServerConfig? = null, val message: String? = null)

/** "Test connection": every candidate's result in rank order, the one now in use and what to say about it. */
data class ConnectionTest(val results: List<ProbeResult>, val chosen: PlexCandidate?, val note: String?)

/**
 * Chooses the address the app uses for each Plex server and keeps it as the server's `baseUrl` (so every caller uses
 * it). The connections plex.tv listed are kept in [store]. A re-probe runs when a cached address fails to connect, when
 * the network changes ([watchNetwork]) and before the first sync of a server that has no choice yet; never more than
 * once per 30 s per server, except when the user asks (force). One probe per server at a time.
 */
class PlexConnectionManager(
    private val servers: ServerRepository,
    private val store: ServerStatusStore,
    private val probe: PlexConnectionProbe,
    private val lookup: PlexCandidateLookup,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val locksGuard = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    private suspend fun lockFor(serverId: String): Mutex = locksGuard.withLock { locks.getOrPut(serverId) { Mutex() } }

    /** Keeps the connections of a server just added, with the one it was saved with. */
    suspend fun remember(serverId: String, candidates: List<PlexCandidate>, chosen: PlexCandidate?, failures: List<ProbeFailure> = emptyList()) {
        store.update(serverId) {
            it.copy(
                candidates = candidates.map(StoredCandidate::of),
                chosenUri = chosen?.uri,
                lastProbeAtMs = nowMs(),
                probeFailures = failures.map(::storedFailure),
                probeNote = connectionNote(ConnectionPick(chosen, failures)),
            )
        }
    }

    /**
     * Probes [serverId]'s connections and switches its address to the one that answers. Null when it did not run: not a
     * signed-in Plex server, or a probe ran less than 30 s ago and [force] is false.
     */
    suspend fun reprobe(serverId: String, force: Boolean = false): ConnectionPick? = lockFor(serverId).withLock {
        val server = plexServer(serverId) ?: return@withLock null
        val record = store.get(serverId)
        if (!force && !reprobeAllowed(record.lastProbeAtMs, nowMs())) return@withLock null
        val candidates = candidatesOf(server, record, refresh = false)
        val pick = pickConnection(candidates) { uri -> probe.probe(uri, server.token) }
        store.update(serverId) {
            it.copy(
                lastProbeAtMs = nowMs(),
                probeFailures = pick.failures.map(::storedFailure),
                probeNote = connectionNote(pick),
                chosenUri = pick.winner?.uri ?: it.chosenUri,
            )
        }
        pick.winner?.let { switchTo(serverId, it.uri) }
        pick
    }

    /** The server to sync: a Plex server that has no chosen connection yet is probed first (throttled). */
    suspend fun beforeSync(server: ServerConfig): ServerConfig {
        if (server.source != ServerSource.PLEX || server.needsSignIn) return server
        if (store.get(server.id).chosenUri != null) return server
        reprobe(server.id)
        return servers.getById(server.id) ?: server
    }

    /**
     * After a failed call to [server]: when [error] is a connection failure (name not found, timeout, refused), re-probe
     * ([force] skips the 30 s wait) and hand back the server to retry with when its address changed. With no address
     * that answers, [FailureOutcome.message] says why.
     */
    suspend fun afterFailure(server: ServerConfig, error: Throwable, force: Boolean): FailureOutcome {
        if (server.source != ServerSource.PLEX || !isConnectionFailure(error)) return FailureOutcome()
        val pick = reprobe(server.id, force) ?: return FailureOutcome()
        val winner = pick.winner ?: return FailureOutcome(message = connectionNote(pick))
        val fresh = servers.getById(server.id) ?: return FailureOutcome()
        return if (winner.uri != server.baseUrl) FailureOutcome(retryWith = fresh) else FailureOutcome()
    }

    /** Asks plex.tv for the connections again, tries all of them at once, and switches to the best one that answers. */
    suspend fun testConnection(serverId: String): ConnectionTest? = lockFor(serverId).withLock {
        val server = plexServer(serverId) ?: return@withLock null
        val candidates = candidatesOf(server, store.get(serverId), refresh = true)
        val results = probeAll(candidates) { uri -> probe.probe(uri, server.token) }
        val chosen = results.winner()
        val failures = results.mapNotNull { r -> r.problem?.let { ProbeFailure(r.candidate, it) } }
        // The note reads like a pick: only the failures ranked above the one chosen matter.
        val ahead = if (chosen == null) failures else failures.filter { f -> candidates.indexOf(f.candidate) < candidates.indexOf(chosen) }
        val note = connectionNote(ConnectionPick(chosen, ahead))
        store.update(serverId) {
            it.copy(
                lastProbeAtMs = nowMs(),
                probeFailures = ahead.map(::storedFailure),
                probeNote = note,
                chosenUri = chosen?.uri ?: it.chosenUri,
            )
        }
        chosen?.let { switchTo(serverId, it.uri) }
        ConnectionTest(results, chosen, note)
    }

    /**
     * Re-probes every signed-in Plex server whenever [network] names a new network (its first value too, so a stale
     * address from the last run is checked at start). Inside the 30 s window it waits for the window to end; a newer
     * change restarts the wait.
     */
    fun watchNetwork(scope: CoroutineScope, network: Flow<String?>) {
        scope.launch {
            network.distinctUntilChanged().filterNotNull().collectLatest {
                val plexIds = try {
                    servers.observeAll().first().filter { it.source == ServerSource.PLEX && !it.needsSignIn }.map { it.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                for (id in plexIds) {
                    try {
                        val last = store.get(id).lastProbeAtMs
                        val now = nowMs()
                        if (last != null && !reprobeAllowed(last, now)) delay(REPROBE_INTERVAL_MS - (now - last))
                        reprobe(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best effort: the next change or failure tries again.
                    }
                }
            }
        }
    }

    private suspend fun plexServer(serverId: String): ServerConfig? =
        servers.getById(serverId)?.takeIf { it.source == ServerSource.PLEX && !it.needsSignIn && it.baseUrl.isNotBlank() }

    /** The stored list; when there is none (or [refresh]) plex.tv's, kept for next time; else the saved address alone. */
    private suspend fun candidatesOf(server: ServerConfig, record: ServerStatusRecord, refresh: Boolean): List<PlexCandidate> {
        val stored = record.candidates.map { it.toCandidate() }
        if (stored.isNotEmpty() && !refresh) return stored
        val looked = try {
            lookup.candidatesFor(server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (!looked.isNullOrEmpty()) {
            store.update(server.id) { it.copy(candidates = looked.map(StoredCandidate::of)) }
            return looked
        }
        return stored.ifEmpty { listOf(PlexCandidate(server.baseUrl.trimEnd('/'), PlexConnectionKind.SAVED)) }
    }

    /** Points the server row at [uri]; read fresh, so a token saved meanwhile is kept. */
    private suspend fun switchTo(serverId: String, uri: String) {
        val fresh = servers.getById(serverId) ?: return
        if (fresh.baseUrl.trimEnd('/') == uri.trimEnd('/')) return
        servers.save(fresh.copy(baseUrl = uri))
    }

    private fun storedFailure(f: ProbeFailure) = StoredProbeFailure(f.candidate.uri, f.candidate.kind.name, f.problem.text)
}
