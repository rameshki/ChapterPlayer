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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Tries one Plex address: `GET <uri>/identity`, which needs no token, so none is sent. Null when the server named
 * [machineId] answered (any Plex server when [machineId] is null); a success from another device is a failure.
 * Never throws.
 */
fun interface PlexConnectionProbe {
    suspend fun probe(uri: String, machineId: String?): ConnectionProblem?
}

/** A server's `machineIdentifier` (null when plex.tv did not give one) and its connections, ranked. */
data class PlexServerConnections(val machineId: String?, val candidates: List<PlexCandidate>)

/**
 * The server's connections as plex.tv lists them now, or null when plex.tv cannot say (no answer, or no server on the
 * account has the server's address). For servers saved before the app kept the list, and for "Test connection".
 */
fun interface PlexCandidateLookup {
    suspend fun candidatesFor(server: ServerConfig): PlexServerConnections?
}

/** What to do after a failed call: retry with [retryWith] (the address changed), or show [message]. */
data class FailureOutcome(val retryWith: ServerConfig? = null, val message: String? = null)

/** "Test connection": every candidate's result in rank order, the one now in use and what to say about it. */
data class ConnectionTest(val results: List<ProbeResult>, val chosen: PlexCandidate?, val note: String?)

/** How long after a remote or relay pick the local connections are tried again (once per network). */
const val LOCAL_RETRY_DELAY_MS = 60_000L

/**
 * Chooses the address the app uses for each Plex server and keeps it as the server's `baseUrl` (so every caller uses
 * it). The connections plex.tv listed and the server's machine id are kept in [store]. A probe sends no token and only
 * counts when the answer names the server's machine id.
 *
 * The token may go to the address in use and to [ServerStatusRecord.verifiedUris]: https connections that answered as
 * this server (their certificate is bound to it) and a plain http address (the raw LAN one) only while it is verified
 * on the current network. On a network change plain http addresses are dropped from that list, and a server using one
 * is moved back to its first https connection before it is probed again, so a LAN address from another network never
 * gets the token.
 *
 * A re-probe runs when a cached address fails to connect, when the network changes ([watchNetwork]) and before the
 * first sync of a server that has no choice yet; never more than once per 30 s per server, except when the user asks
 * (force) or the address in use is not verified. One probe per server at a time. [onSwitched] hears of every address
 * change a caller did not ask for itself (the app syncs that server again, so stored covers follow).
 */
class PlexConnectionManager(
    private val servers: ServerRepository,
    private val store: ServerStatusStore,
    private val probe: PlexConnectionProbe,
    private val lookup: PlexCandidateLookup,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Where probes and plex.tv's answer run. */
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Told when a server's address changed; set once the syncer exists. */
    var onSwitched: (serverId: String) -> Unit = {}

    private val locksGuard = Mutex()
    private val locks = mutableMapOf<String, Mutex>()

    private suspend fun lockFor(serverId: String): Mutex = locksGuard.withLock { locks.getOrPut(serverId) { Mutex() } }

    /** Keeps the connections of a server just added (or signed in again), with the one it was saved with. */
    suspend fun remember(
        serverId: String,
        machineId: String?,
        candidates: List<PlexCandidate>,
        chosen: PlexCandidate?,
        failures: List<ProbeFailure> = emptyList(),
    ) {
        store.update(serverId) {
            it.copy(
                candidates = candidates.map(StoredCandidate::of),
                machineId = machineId,
                chosenUri = chosen?.uri,
                verifiedUris = listOfNotNull(chosen?.uri),
                lastProbeAtMs = nowMs(),
                probeFailures = failures.map(::storedFailure),
                probeNote = connectionNote(ConnectionPick(chosen, failures)),
                probeFailed = chosen == null,
            )
        }
    }

    /** Forgets what is known about the server's connections (its address or token changed by hand). */
    suspend fun forget(serverId: String) {
        store.update(serverId) {
            it.copy(
                candidates = emptyList(), machineId = null, chosenUri = null, verifiedUris = emptyList(),
                lastProbeAtMs = null, probeFailures = emptyList(), probeNote = null, probeFailed = false,
            )
        }
    }

    /**
     * Probes [serverId]'s connections and switches its address to the one that answers. Null when it did not run: not a
     * signed-in Plex server, or a probe ran less than 30 s ago and [force] is false. [notify] tells [onSwitched].
     */
    suspend fun reprobe(serverId: String, force: Boolean = false, notify: Boolean = true): ConnectionPick? =
        lockFor(serverId).withLock {
            val server = plexServer(serverId) ?: return@withLock null
            val record = store.get(serverId)
            if (!force && !reprobeAllowed(record.lastProbeAtMs, nowMs())) return@withLock null
            val (machineId, candidates) = connectionsOf(server, record, refresh = false)
            val pick = withContext(io) { pickConnection(candidates) { uri -> probe.probe(uri, machineId) } }
            // The address and the record change together, so a cancel cannot leave one without the other.
            withContext(NonCancellable) {
                val switched = pick.winner?.let { switchTo(serverId, it.uri) } ?: false
                store.update(serverId) { r ->
                    r.copy(
                        lastProbeAtMs = nowMs(),
                        probeFailures = pick.failures.map(::storedFailure),
                        probeNote = connectionNote(pick),
                        probeFailed = pick.winner == null,
                        chosenUri = pick.winner?.uri ?: r.chosenUri,
                        verifiedUris = verified(r, machineId, listOfNotNull(pick.winner?.uri)),
                    )
                }
                if (switched && notify) onSwitched(serverId)
            }
            pick
        }

    /**
     * The server to sync: a Plex server that has no chosen connection yet is probed first (throttled), and one whose
     * plain http address is not verified on this network is probed at once.
     */
    suspend fun beforeSync(server: ServerConfig): ServerConfig {
        if (server.source != ServerSource.PLEX || server.needsSignIn) return server
        val record = store.get(server.id)
        when {
            record.chosenUri == null -> reprobe(server.id, notify = false)
            unverifiedHttp(server.baseUrl, record) -> {
                reprobe(server.id, force = true, notify = false)
                // Still not verified here: the token must not go to that plain address; use the https one.
                val after = servers.getById(server.id)
                if (after != null && unverifiedHttp(after.baseUrl, store.get(server.id))) withContext(NonCancellable) { dropHttpTrust(server.id) }
            }
            else -> return server
        }
        return servers.getById(server.id) ?: server
    }

    /**
     * After a failed call to [server]: when the stored address has changed meanwhile, retry on it without probing.
     * Otherwise, when [error] is a connection failure (name not found, timeout, refused), re-probe ([force] skips the
     * 30 s wait) and hand back the server to retry with when its address changed. With no address that answers,
     * [FailureOutcome.message] says why. The caller retries itself, so [notify] is for callers that do not.
     */
    suspend fun afterFailure(server: ServerConfig, error: Throwable, force: Boolean, notify: Boolean = false): FailureOutcome {
        if (server.source != ServerSource.PLEX) return FailureOutcome()
        val current = servers.getById(server.id) ?: return FailureOutcome()
        if (current.baseUrl.trimEnd('/') != server.baseUrl.trimEnd('/')) return FailureOutcome(retryWith = current)
        if (!isConnectionFailure(error)) return FailureOutcome()
        val pick = reprobe(server.id, force, notify) ?: return FailureOutcome()
        val winner = pick.winner ?: return FailureOutcome(message = connectionNote(pick))
        val fresh = servers.getById(server.id) ?: return FailureOutcome()
        return if (winner.uri.trimEnd('/') != server.baseUrl.trimEnd('/')) FailureOutcome(retryWith = fresh) else FailureOutcome()
    }

    /** Asks plex.tv for the connections again, tries all of them at once, and switches to the best one that answers. */
    suspend fun testConnection(serverId: String): ConnectionTest? = lockFor(serverId).withLock {
        val server = plexServer(serverId) ?: return@withLock null
        val (machineId, candidates) = connectionsOf(server, store.get(serverId), refresh = true)
        val results = withContext(io) { probeAll(candidates) { uri -> probe.probe(uri, machineId) } }
        val chosen = results.winner()
        val failures = results.mapNotNull { r -> r.problem?.let { ProbeFailure(r.candidate, it) } }
        // The note reads like a pick: only the failures ranked above the one chosen matter.
        val ahead = if (chosen == null) failures else failures.filter { f -> candidates.indexOf(f.candidate) < candidates.indexOf(chosen) }
        val note = connectionNote(ConnectionPick(chosen, ahead))
        withContext(NonCancellable) {
            val switched = chosen?.let { switchTo(serverId, it.uri) } ?: false
            store.update(serverId) { r ->
                r.copy(
                    lastProbeAtMs = nowMs(),
                    probeFailures = ahead.map(::storedFailure),
                    probeNote = note,
                    probeFailed = chosen == null,
                    chosenUri = chosen?.uri ?: r.chosenUri,
                    // Every connection that answered as this server is verified on this network now.
                    verifiedUris = verified(r, machineId, results.filter { it.problem == null }.map { it.candidate.uri }),
                )
            }
            if (switched) onSwitched(serverId)
        }
        ConnectionTest(results, chosen, note)
    }

    /**
     * On each network [network] names (its first value too, since the last run may have been elsewhere): every
     * signed-in Plex server forgets its plain http verifications, leaves a plain http address for its https connection,
     * and is probed again (inside the 30 s window it waits for the window to end, unless it was moved). When the pick
     * is remote or relay, its local connections are tried once more a minute later and win if they answer. A newer
     * network change cancels all of that and starts over.
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
                coroutineScope {
                    for (id in plexIds) launch { onNetworkChanged(id) }
                }
            }
        }
    }

    private suspend fun onNetworkChanged(serverId: String) {
        try {
            val before = servers.getById(serverId)?.baseUrl
            val moved = withContext(NonCancellable) { dropHttpTrust(serverId) }
            val last = store.get(serverId).lastProbeAtMs
            val now = nowMs()
            if (!moved && last != null && !reprobeAllowed(last, now)) delay(REPROBE_INTERVAL_MS - (now - last))
            val pick = reprobe(serverId, force = true, notify = false)
            // Moved away and back again is no change; anything else is, and the server is synced again.
            if (servers.getById(serverId)?.baseUrl != before) onSwitched(serverId)
            if (pick == null) return
            val kind = pick.winner?.kind ?: return
            if (kind == PlexConnectionKind.REMOTE || kind == PlexConnectionKind.RELAY) {
                delay(LOCAL_RETRY_DELAY_MS)
                promoteLocal(serverId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the next change or failure tries again.
        }
    }

    /** Drops plain http verifications; a server using a plain http address goes back to its first https one. */
    private suspend fun dropHttpTrust(serverId: String): Boolean {
        val record = store.get(serverId)
        store.update(serverId) { r -> r.copy(verifiedUris = r.verifiedUris.filter(::isHttps)) }
        val server = servers.getById(serverId) ?: return false
        if (record.machineId == null || isHttps(server.baseUrl)) return false
        val https = record.candidates.map { it.toCandidate() }.firstOrNull { isHttps(it.uri) } ?: return false
        return switchTo(serverId, https.uri)
    }

    /** Tries only the local connections; when one answers as this server, it replaces a remote or relay choice. */
    private suspend fun promoteLocal(serverId: String): Unit = lockFor(serverId).withLock {
        plexServer(serverId) ?: return@withLock
        val record = store.get(serverId)
        val locals = record.candidates.map { it.toCandidate() }
            .filter { it.kind == PlexConnectionKind.LOCAL || it.kind == PlexConnectionKind.LOCAL_ADDRESS }
        if (locals.isEmpty()) return@withLock
        val pick = withContext(io) { pickConnection(locals) { uri -> probe.probe(uri, record.machineId) } }
        val winner = pick.winner ?: return@withLock
        withContext(NonCancellable) {
            val switched = switchTo(serverId, winner.uri)
            store.update(serverId) { r ->
                r.copy(
                    lastProbeAtMs = nowMs(),
                    chosenUri = winner.uri,
                    probeFailures = pick.failures.map(::storedFailure),
                    probeNote = connectionNote(pick),
                    probeFailed = false,
                    verifiedUris = verified(r, record.machineId, listOf(winner.uri)),
                )
            }
            if (switched) onSwitched(serverId)
        }
    }

    private suspend fun plexServer(serverId: String): ServerConfig? =
        servers.getById(serverId)?.takeIf { it.source == ServerSource.PLEX && !it.needsSignIn && it.baseUrl.isNotBlank() }

    /** The stored list; when there is none (or [refresh]) plex.tv's, kept for next time; else the saved address alone. */
    private suspend fun connectionsOf(server: ServerConfig, record: ServerStatusRecord, refresh: Boolean): PlexServerConnections {
        val stored = record.candidates.map { it.toCandidate() }
        if (stored.isNotEmpty() && !refresh) return PlexServerConnections(record.machineId, stored)
        val looked = try {
            withContext(io) { lookup.candidatesFor(server) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (looked != null && looked.candidates.isNotEmpty()) {
            store.update(server.id) { r ->
                // A different server under the same address: nothing verified before counts.
                val sameServer = r.machineId == null || r.machineId == looked.machineId
                r.copy(
                    candidates = looked.candidates.map(StoredCandidate::of),
                    machineId = looked.machineId ?: r.machineId,
                    verifiedUris = if (sameServer) r.verifiedUris else emptyList(),
                )
            }
            return PlexServerConnections(looked.machineId ?: record.machineId, looked.candidates)
        }
        return PlexServerConnections(
            record.machineId,
            stored.ifEmpty { listOf(PlexCandidate(server.baseUrl.trimEnd('/'), PlexConnectionKind.SAVED)) }
        )
    }

    /**
     * The verified addresses after a probe that found [answered]: the earlier https ones that are still listed, plus
     * what answered now. Without a machine id nothing is verified (the answer could not be checked).
     */
    private fun verified(record: ServerStatusRecord, machineId: String?, answered: List<String>): List<String> {
        if (machineId == null) return emptyList()
        val listed = record.candidates.map { it.uri }.toSet()
        return (record.verifiedUris.filter { isHttps(it) && it in listed } + answered).distinct()
    }

    private fun unverifiedHttp(baseUrl: String, record: ServerStatusRecord): Boolean =
        record.machineId != null && !isHttps(baseUrl) && baseUrl.trimEnd('/') !in record.verifiedUris.map { it.trimEnd('/') }

    /** Points the server row at [uri]; read fresh, so a token saved meanwhile is kept. True when it changed. */
    private suspend fun switchTo(serverId: String, uri: String): Boolean {
        val fresh = servers.getById(serverId) ?: return false
        if (fresh.baseUrl.trimEnd('/') == uri.trimEnd('/')) return false
        servers.save(fresh.copy(baseUrl = uri))
        return true
    }

    private fun storedFailure(f: ProbeFailure) = StoredProbeFailure(f.candidate.uri, f.candidate.kind.name, f.problem.text)
}

private fun isHttps(uri: String) = uri.trim().startsWith("https://", ignoreCase = true)
