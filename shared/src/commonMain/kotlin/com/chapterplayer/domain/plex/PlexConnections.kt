package com.chapterplayer.domain.plex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How the app reaches a Plex server. plex.tv lists several connections per server: on the home network (local), from
 * outside through the router (remote) and through Plex's relay. All of them use `*.plex.direct` names, which a router
 * with DNS rebinding protection (a Fritz!Box, for one) refuses to resolve when they point at a private address, so the
 * raw `http://<address>:<port>` of a local connection is tried as well ([LOCAL_ADDRESS]).
 */
enum class PlexConnectionKind(val label: String) {
    LOCAL("Local"),
    LOCAL_ADDRESS("Local address"),
    REMOTE("Remote"),
    RELAY("Relay"),
    /** The address saved before the app knew the server's connections. */
    SAVED("Saved address"),
}

/** One connection as plex.tv's `/api/v2/resources` lists it. */
data class PlexConnectionInfo(
    val uri: String,
    val address: String = "",
    val port: Int = 0,
    val protocol: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
    val ipv6: Boolean = false,
)

/** An address to try, with what kind of connection it is. */
data class PlexCandidate(val uri: String, val kind: PlexConnectionKind)

/** What went wrong with one try. Words only: never an address, never a token. */
enum class ProblemKind { UNKNOWN_HOST, TIMEOUT, REFUSED, UNREACHABLE, TLS, HTTP, WRONG_SERVER, OTHER }

data class ConnectionProblem(val kind: ProblemKind, val httpStatus: Int? = null) {
    val text: String
        get() = when (kind) {
            ProblemKind.UNKNOWN_HOST -> "name not found"
            ProblemKind.TIMEOUT -> "timeout"
            ProblemKind.REFUSED -> "connection refused"
            ProblemKind.UNREACHABLE -> "network unreachable"
            ProblemKind.TLS -> "secure connection failed"
            ProblemKind.HTTP -> when (httpStatus) {
                401, 403 -> "HTTP $httpStatus: token refused"
                null -> "unexpected answer"
                else -> "HTTP $httpStatus"
            }
            ProblemKind.WRONG_SERVER -> "another device answered"
            ProblemKind.OTHER -> "could not connect"
        }
}

data class ProbeFailure(val candidate: PlexCandidate, val problem: ConnectionProblem)

/** The connection that answered first ([winner], null when none did) and the ones that failed, in rank order. */
data class ConnectionPick(val winner: PlexCandidate?, val failures: List<ProbeFailure>)

/** One candidate's result in a full test; [problem] is null when it answered. */
data class ProbeResult(val candidate: PlexCandidate, val problem: ConnectionProblem?)

/** The best-ranked candidate that answered. */
fun List<ProbeResult>.winner(): PlexCandidate? = firstOrNull { it.problem == null }?.candidate

/** How long one probe may take in all, on top of the HTTP client's own 5 s connect and read timeouts. */
const val PROBE_TIMEOUT_MS = 11_000L

/**
 * When a lower-ranked connection answers first, better-ranked ones still trying get this long to answer too (so a
 * remote answer does not beat the local one by a few milliseconds).
 */
const val PROBE_GRACE_MS = 1_500L

/** At most one re-probe of a server per this long (a forced one, from the user, ignores it). */
const val REPROBE_INTERVAL_MS = 30_000L

private const val PLEX_DIRECT_SUFFIX = ".plex.direct"

/**
 * The connections in the order to try them: local first, then remote, then relay. IPv6 connections are used only when
 * there is nothing else. Each local `plex.direct` connection also gives its raw `http://<address>:<port>` (not when the
 * server requires https): with [dnsRebindingProtection] (plex.tv saw a network that blocks such names) right after it,
 * otherwise after the remote ones, as a last way in on the home network before the relay. Duplicates are dropped.
 */
fun rankPlexConnections(
    connections: List<PlexConnectionInfo>,
    dnsRebindingProtection: Boolean,
    httpsRequired: Boolean = false,
): List<PlexCandidate> {
    val usable = connections.filter { it.uri.isNotBlank() }
    val v4 = usable.filter { !it.ipv6 }
    val pool = v4.ifEmpty { usable }
    val locals = pool.filter { it.local && !it.relay }
    val remotes = pool.filter { !it.local && !it.relay }
    val relays = pool.filter { it.relay }

    fun raw(c: PlexConnectionInfo): PlexCandidate? {
        if (httpsRequired || c.address.isBlank() || c.port <= 0) return null
        if (!hostOf(c.uri).endsWith(PLEX_DIRECT_SUFFIX)) return null
        val host = if (c.address.contains(':')) "[${c.address}]" else c.address
        return PlexCandidate("http://$host:${c.port}", PlexConnectionKind.LOCAL_ADDRESS)
    }
    fun candidate(c: PlexConnectionInfo, kind: PlexConnectionKind) = PlexCandidate(c.uri.trim().trimEnd('/'), kind)

    val ordered = buildList {
        if (dnsRebindingProtection) {
            locals.forEach { add(candidate(it, PlexConnectionKind.LOCAL)); raw(it)?.let(::add) }
            remotes.forEach { add(candidate(it, PlexConnectionKind.REMOTE)) }
        } else {
            locals.forEach { add(candidate(it, PlexConnectionKind.LOCAL)) }
            remotes.forEach { add(candidate(it, PlexConnectionKind.REMOTE)) }
            locals.forEach { c -> raw(c)?.let(::add) }
        }
        relays.forEach { add(candidate(it, PlexConnectionKind.RELAY)) }
    }
    val seen = mutableSetOf<String>()
    return ordered.filter { seen.add(it.uri.lowercase()) }
}

/**
 * Tries [candidates] in rank order with [probe] (null = the right server answered, else what went wrong; a throw is
 * classified with [classifyConnectionError]). Two tries run at a time. A connection wins once every better-ranked one
 * has failed, or when better-ranked ones are still trying [graceMs] after it answered. A timeout is tried once more (a
 * phone's radio can be slow to wake). When a local `plex.direct` name is not found, the raw local addresses not tried
 * yet move up to right after it, ahead of remote and relay, whatever plex.tv said about DNS rebinding.
 */
suspend fun pickConnection(
    candidates: List<PlexCandidate>,
    probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    graceMs: Long = PROBE_GRACE_MS,
    probe: suspend (uri: String) -> ConnectionProblem?,
): ConnectionPick = coroutineScope {
    val order = candidates.distinct().toMutableList()
    val done = HashMap<PlexCandidate, ConnectionProblem?>()
    val running = mutableSetOf<PlexCandidate>()
    val events = Channel<PickEvent>(Channel.UNLIMITED)
    var graceJob: Job? = null
    var graceFor: PlexCandidate? = null

    fun start(c: PlexCandidate) {
        running += c
        launch { events.send(PickEvent.Done(c, probeWithRetry(c.uri, probeTimeoutMs, probe))) }
    }
    fun failuresInOrder() = order.mapNotNull { c -> done[c]?.let { ProbeFailure(c, it) } }
    fun answered(c: PlexCandidate) = c in done && done[c] == null

    var pick: ConnectionPick? = null
    while (pick == null) {
        // The winner: the first in rank order that answered, with everything ranked above it failed.
        val decided = order.firstOrNull { it !in done || answered(it) }?.takeIf { answered(it) }
        if (decided != null) { pick = ConnectionPick(decided, failuresInOrder()); continue }
        val provisional = order.firstOrNull { answered(it) }
        if (provisional != null) {
            // Better-ranked ones get their chance: start them now, and give them the grace window.
            order.takeWhile { it != provisional }.filter { it !in done && it !in running }.forEach(::start)
            if (graceFor != provisional) {
                graceJob?.cancel()
                graceFor = provisional
                graceJob = launch { delay(graceMs); events.send(PickEvent.Grace(provisional)) }
            }
        } else {
            for (c in order) {
                if (running.size >= 2) break
                if (c !in done && c !in running) start(c)
            }
            if (running.isEmpty()) { pick = ConnectionPick(null, failuresInOrder()); continue }
        }
        when (val event = events.receive()) {
            is PickEvent.Done -> {
                running -= event.candidate
                done[event.candidate] = event.problem
                if (event.problem?.kind == ProblemKind.UNKNOWN_HOST && event.candidate.kind == PlexConnectionKind.LOCAL &&
                    hostOf(event.candidate.uri).endsWith(PLEX_DIRECT_SUFFIX)
                ) {
                    val raw = order.filter { it.kind == PlexConnectionKind.LOCAL_ADDRESS && it !in done && it !in running }
                    if (raw.isNotEmpty()) {
                        order.removeAll(raw)
                        order.addAll(order.indexOf(event.candidate) + 1, raw)
                        graceFor = null // a fresh window for the moved addresses
                    }
                }
            }
            is PickEvent.Grace -> if (event.candidate == order.firstOrNull { answered(it) }) {
                pick = ConnectionPick(event.candidate, failuresInOrder())
            }
        }
    }
    coroutineContext.cancelChildren()
    checkNotNull(pick)
}

private sealed interface PickEvent {
    data class Done(val candidate: PlexCandidate, val problem: ConnectionProblem?) : PickEvent
    data class Grace(val candidate: PlexCandidate) : PickEvent
}

/** Tries every candidate at the same time (for "Test connection"); the results come back in rank order. */
suspend fun probeAll(
    candidates: List<PlexCandidate>,
    probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    probe: suspend (uri: String) -> ConnectionProblem?,
): List<ProbeResult> = coroutineScope {
    candidates.map { candidate -> async { ProbeResult(candidate, probeWithRetry(candidate.uri, probeTimeoutMs, probe)) } }.awaitAll()
}

/** One try, and one more after a timeout. */
private suspend fun probeWithRetry(
    uri: String,
    timeoutMs: Long,
    probe: suspend (uri: String) -> ConnectionProblem?,
): ConnectionProblem? {
    val first = safeProbe(uri, timeoutMs, probe)
    return if (first?.kind == ProblemKind.TIMEOUT) safeProbe(uri, timeoutMs, probe) else first
}

private suspend fun safeProbe(
    uri: String,
    timeoutMs: Long,
    probe: suspend (uri: String) -> ConnectionProblem?,
): ConnectionProblem? {
    var problem: ConnectionProblem? = null
    val finished = withTimeoutOrNull(timeoutMs) {
        problem = try {
            probe(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifyConnectionError(e)
        }
        true
    }
    return if (finished == null) ConnectionProblem(ProblemKind.TIMEOUT) else problem
}

/**
 * What kind of connection failure [error] is, by the type names in its cause chain (common code cannot name the JVM
 * types): `UnknownHostException` is a name the network would not resolve, a `...Timeout...` type a timeout,
 * `ConnectException` a refused connection, an SSL or certificate type a failed secure connection.
 */
fun classifyConnectionError(error: Throwable): ConnectionProblem {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 10) {
        val name = current::class.simpleName.orEmpty()
        val kind = when {
            name == "UnknownHostException" || name == "UnresolvedAddressException" -> ProblemKind.UNKNOWN_HOST
            name.contains("Timeout") -> ProblemKind.TIMEOUT
            name == "InterruptedIOException" && current.message?.contains("timeout", ignoreCase = true) == true -> ProblemKind.TIMEOUT
            name == "ConnectException" -> ProblemKind.REFUSED
            name == "NoRouteToHostException" || name == "PortUnreachableException" -> ProblemKind.UNREACHABLE
            name.startsWith("SSL") || name.contains("Certificate") -> ProblemKind.TLS
            else -> null
        }
        if (kind != null) return ConnectionProblem(kind)
        if (current.cause === current) break
        current = current.cause
        depth++
    }
    return ConnectionProblem(ProblemKind.OTHER)
}

/** Whether [error] means the address could not be reached (so another connection may work), not a refusal by the server. */
fun isConnectionFailure(error: Throwable): Boolean = classifyConnectionError(error).kind != ProblemKind.OTHER

/** Whether a re-probe may run now: never more than once per [intervalMs]; a clock that went back allows it. */
fun reprobeAllowed(lastProbeAtMs: Long?, nowMs: Long, intervalMs: Long = REPROBE_INTERVAL_MS): Boolean =
    lastProbeAtMs == null || nowMs < lastProbeAtMs || nowMs - lastProbeAtMs >= intervalMs

/**
 * What the Servers page says about a pick, or null when there is nothing to say. A local `plex.direct` name that the
 * network would not resolve is said in words, whether the raw local address got around it or not.
 */
fun connectionNote(pick: ConnectionPick): String? {
    val blockedName = pick.failures.any {
        it.candidate.kind == PlexConnectionKind.LOCAL && it.problem.kind == ProblemKind.UNKNOWN_HOST &&
            hostOf(it.candidate.uri).endsWith(PLEX_DIRECT_SUFFIX)
    }
    val winner = pick.winner
    if (winner != null) {
        return if (blockedName && winner.kind == PlexConnectionKind.LOCAL_ADDRESS) {
            "This network blocks the plex.direct name; using the local address"
        } else if (blockedName) {
            "This network blocks the plex.direct name; using the ${winner.kind.label.lowercase()} connection"
        } else null
    }
    if (blockedName) {
        val raw = pick.failures.firstOrNull { it.candidate.kind == PlexConnectionKind.LOCAL_ADDRESS }
        if (raw != null) return "This network blocks the plex.direct name; the local address did not answer either (${raw.problem.text})"
        return "This network blocks the plex.direct name; no other address answered"
    }
    val first = pick.failures.firstOrNull() ?: return "Could not reach the server: no address to try"
    return "Could not reach the server: ${first.problem.text}"
}

/** The host of [uri], lower case, without port or brackets. */
fun hostOf(uri: String): String {
    val afterScheme = uri.substringAfter("://", uri)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
    val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
    return host.lowercase()
}
