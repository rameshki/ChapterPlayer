package com.chapterplayer.domain.plex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlexConnectionsTest {

    private fun conn(
        uri: String,
        local: Boolean = false,
        relay: Boolean = false,
        ipv6: Boolean = false,
        address: String = "",
        port: Int = 32400,
        protocol: String = "https",
    ) = PlexConnectionInfo(uri = uri, address = address, port = port, protocol = protocol, local = local, relay = relay, ipv6 = ipv6)

    private val localDirect = conn("https://192-0-2-11.abc.plex.direct:32400", local = true, address = "192.0.2.11")
    private val remoteDirect = conn("https://198-51-100-1.abc.plex.direct:32400", address = "198.51.100.1")
    private val relay = conn("https://198-51-100-200.abc.plex.direct:8443", relay = true, address = "198.51.100.200", port = 8443)

    // ── Ranking ────────────────────────────────────────────────────────────

    @Test
    fun `local comes before remote and remote before relay`() {
        val ranked = rankPlexConnections(listOf(relay, remoteDirect, localDirect), dnsRebindingProtection = false)

        assertEquals(
            listOf(PlexConnectionKind.LOCAL, PlexConnectionKind.REMOTE, PlexConnectionKind.LOCAL_ADDRESS, PlexConnectionKind.RELAY),
            ranked.map { it.kind }
        )
        assertEquals(localDirect.uri, ranked[0].uri)
    }

    @Test
    fun `with DNS rebinding protection the raw local address follows the plex direct name at once`() {
        val ranked = rankPlexConnections(listOf(localDirect, remoteDirect, relay), dnsRebindingProtection = true)

        assertEquals(
            listOf(localDirect.uri, "http://192.0.2.11:32400", remoteDirect.uri, relay.uri),
            ranked.map { it.uri }
        )
        assertEquals(PlexConnectionKind.LOCAL_ADDRESS, ranked[1].kind)
    }

    @Test
    fun `a plain local address is not doubled`() {
        val plain = conn("http://192.0.2.11:32400", local = true, address = "192.0.2.11", protocol = "http")

        val ranked = rankPlexConnections(listOf(localDirect, plain), dnsRebindingProtection = true)

        assertEquals(listOf(localDirect.uri, plain.uri), ranked.map { it.uri })
    }

    @Test
    fun `no raw address when the server requires https`() {
        val ranked = rankPlexConnections(listOf(localDirect), dnsRebindingProtection = true, httpsRequired = true)

        assertEquals(listOf(localDirect.uri), ranked.map { it.uri })
    }

    @Test
    fun `IPv6 connections are skipped while there is anything else`() {
        val v6 = conn("https://2001-db8--1.abc.plex.direct:32400", local = true, ipv6 = true, address = "2001:db8::1")

        assertEquals(
            listOf(remoteDirect.uri),
            rankPlexConnections(listOf(v6, remoteDirect), dnsRebindingProtection = false).map { it.uri }
        )
        val onlyV6 = rankPlexConnections(listOf(v6), dnsRebindingProtection = true)
        assertEquals(listOf(v6.uri, "http://[2001:db8::1]:32400"), onlyV6.map { it.uri })
    }

    @Test
    fun `blank uris are dropped and trailing slashes trimmed`() {
        val ranked = rankPlexConnections(listOf(conn(""), conn("https://x.example:32400/")), dnsRebindingProtection = false)

        assertEquals(listOf("https://x.example:32400"), ranked.map { it.uri })
    }

    // ── Choosing ───────────────────────────────────────────────────────────

    private val a = PlexCandidate("https://a.plex.direct:32400", PlexConnectionKind.LOCAL)
    private val b = PlexCandidate("http://192.0.2.11:32400", PlexConnectionKind.LOCAL_ADDRESS)
    private val c = PlexCandidate("https://c.plex.direct:32400", PlexConnectionKind.REMOTE)
    private val d = PlexCandidate("https://d.plex.direct:8443", PlexConnectionKind.RELAY)

    @Test
    fun `the first candidate that answers wins`() = runTest {
        val pick = pickConnection(listOf(a, b, c)) { null }

        assertEquals(a, pick.winner)
        assertEquals(emptyList(), pick.failures)
    }

    @Test
    fun `the first two run at the same time and a lower-ranked answer wins after the grace window`() = runTest {
        val probed = mutableListOf<String>()
        val pick = pickConnection(listOf(a, b, c)) { uri ->
            probed += uri
            if (uri == a.uri) { delay(5_000); null } else { delay(100); null }
        }

        assertEquals(b, pick.winner)
        assertEquals(100 + PROBE_GRACE_MS, currentTime)
        assertEquals(listOf(a.uri, b.uri), probed)
    }

    @Test
    fun `a better-ranked answer inside the grace window wins`() = runTest {
        val pick = pickConnection(listOf(c, d)) { uri -> if (uri == c.uri) { delay(1_200); null } else { delay(100); null } }

        assertEquals(c, pick.winner)
        assertEquals(1_200, currentTime)
    }

    @Test
    fun `a timeout is tried once more`() = runTest {
        var tries = 0
        val pick = pickConnection(listOf(b)) { tries++; if (tries == 1) ConnectionProblem(ProblemKind.TIMEOUT) else null }

        assertEquals(b, pick.winner)
        assertEquals(2, tries)
    }

    @Test
    fun `a local plex direct name that is not found moves the raw local address ahead of remote`() = runTest {
        // Ranked as plex.tv gives it without the DNS rebinding flag: local, remote, raw local.
        val pick = pickConnection(listOf(a, c, b)) { uri ->
            when (uri) {
                a.uri -> ConnectionProblem(ProblemKind.UNKNOWN_HOST)
                c.uri -> { delay(10); null }
                else -> { delay(200); null }
            }
        }

        assertEquals(b, pick.winner)
        assertEquals(listOf(a), pick.failures.map { it.candidate })
    }

    @Test
    fun `a wrong server is a failure`() = runTest {
        val pick = pickConnection(listOf(b, c)) { uri -> if (uri == b.uri) ConnectionProblem(ProblemKind.WRONG_SERVER) else null }

        assertEquals(c, pick.winner)
        assertEquals("another device answered", pick.failures.single().problem.text)
    }

    @Test
    fun `failures are listed in rank order and the rest are tried one by one`() = runTest {
        val pick = pickConnection(listOf(a, b, c, d)) { uri ->
            when (uri) {
                a.uri -> { delay(50); ConnectionProblem(ProblemKind.UNKNOWN_HOST) }
                b.uri -> ConnectionProblem(ProblemKind.TIMEOUT)
                c.uri -> ConnectionProblem(ProblemKind.REFUSED)
                else -> null
            }
        }

        assertEquals(d, pick.winner)
        assertEquals(listOf(a, b, c), pick.failures.map { it.candidate })
        assertEquals(ProblemKind.UNKNOWN_HOST, pick.failures[0].problem.kind)
    }

    @Test
    fun `a probe that throws is a failure with a classified reason`() = runTest {
        val pick = pickConnection(listOf(a, b)) { uri ->
            if (uri == a.uri) throw UnknownHostException("Unable to resolve host \"a.plex.direct\"") else throw ConnectException("refused")
        }

        assertNull(pick.winner)
        assertEquals(listOf(ProblemKind.UNKNOWN_HOST, ProblemKind.REFUSED), pick.failures.map { it.problem.kind })
    }

    @Test
    fun `a probe that hangs times out`() = runTest {
        val pick = pickConnection(listOf(a), probeTimeoutMs = 4_000) { awaitCancellation() }

        assertNull(pick.winner)
        assertEquals(ProblemKind.TIMEOUT, pick.failures.single().problem.kind)
        // Tried twice: a timeout gets a second chance.
        assertEquals(8_000, currentTime)
    }

    @Test
    fun `the losing probe is cancelled`() = runTest {
        val cancelled = CompletableDeferred<Boolean>()
        pickConnection(listOf(a, b)) { uri ->
            if (uri == a.uri) {
                try { awaitCancellation() } finally { cancelled.complete(true) }
            } else null
        }
        assertTrue(cancelled.await())
    }

    @Test
    fun `no candidates gives no winner`() = runTest {
        val pick = pickConnection(emptyList()) { null }
        assertNull(pick.winner)
        assertEquals(emptyList(), pick.failures)
    }

    @Test
    fun `probing all reports every candidate in rank order and the best ranked answer wins`() = runTest {
        val results = probeAll(listOf(a, b, c)) { uri ->
            when (uri) {
                a.uri -> ConnectionProblem(ProblemKind.UNKNOWN_HOST)
                b.uri -> { delay(500); null }
                else -> null
            }
        }

        assertEquals(listOf(a, b, c), results.map { it.candidate })
        assertEquals(listOf(false, true, true), results.map { it.problem == null })
        assertEquals(b, results.winner())
    }

    // ── Errors and texts ───────────────────────────────────────────────────

    @Test
    fun `errors are classified by type through the cause chain`() {
        assertEquals(ProblemKind.UNKNOWN_HOST, classifyConnectionError(UnknownHostException("x")).kind)
        assertEquals(ProblemKind.TIMEOUT, classifyConnectionError(SocketTimeoutException("x")).kind)
        assertEquals(ProblemKind.REFUSED, classifyConnectionError(ConnectException("x")).kind)
        assertEquals(ProblemKind.TLS, classifyConnectionError(SSLHandshakeException("x")).kind)
        assertEquals(ProblemKind.UNKNOWN_HOST, classifyConnectionError(RuntimeException("wrap", UnknownHostException("x"))).kind)
        assertEquals(ProblemKind.OTHER, classifyConnectionError(IllegalStateException("x")).kind)
        assertTrue(isConnectionFailure(UnknownHostException("x")))
        assertTrue(isConnectionFailure(SocketTimeoutException("x")))
        assertFalse(isConnectionFailure(IllegalStateException("x")))
    }

    /** Stands in for Ktor's Darwin engine error (iOS), which carries the NSError only in its message. */
    private class DarwinHttpRequestException(message: String) : Exception(message)

    private fun darwin(code: Int, domain: String = "NSURLErrorDomain") = DarwinHttpRequestException(
        "Exception in http request: Error Domain=$domain Code=$code \"Some text.\" UserInfo={NSErrorFailingURLStringKey=https://host.example/identity}"
    )

    @Test
    fun `iOS URL errors are classified by their NSURLErrorDomain code`() {
        assertEquals(ProblemKind.UNKNOWN_HOST, classifyConnectionError(darwin(-1003)).kind)
        assertEquals(ProblemKind.UNKNOWN_HOST, classifyConnectionError(darwin(-1006)).kind)
        assertEquals(ProblemKind.TIMEOUT, classifyConnectionError(darwin(-1001)).kind)
        assertEquals(ProblemKind.REFUSED, classifyConnectionError(darwin(-1004)).kind)
        assertEquals(ProblemKind.UNREACHABLE, classifyConnectionError(darwin(-1009)).kind)
        assertEquals(ProblemKind.UNREACHABLE, classifyConnectionError(darwin(-1005)).kind)
        assertEquals(ProblemKind.TLS, classifyConnectionError(darwin(-1200)).kind)
        assertEquals(ProblemKind.TLS, classifyConnectionError(darwin(-1202)).kind)
        assertEquals(ProblemKind.UNKNOWN_HOST, classifyConnectionError(RuntimeException("wrap", darwin(-1003))).kind)
        assertTrue(isConnectionFailure(darwin(-1001)))
    }

    @Test
    fun `iOS keeping the app off the local network is its own problem, not plain unreachable`() {
        val prohibited = DarwinHttpRequestException(
            "Exception in http request: Error Domain=NSURLErrorDomain Code=-1009 \"The Internet connection appears to be offline.\" " +
                "UserInfo={_NSURLErrorNWPathKey=unsatisfied (Local network prohibited), interface: en0[802.11]}"
        )
        assertEquals(ProblemKind.LOCAL_NETWORK_OFF, classifyConnectionError(prohibited).kind)
        assertEquals("local network access is off", ConnectionProblem(ProblemKind.LOCAL_NETWORK_OFF).text)
        assertTrue(isConnectionFailure(prohibited))
        // Offline without that path note stays unreachable.
        assertEquals(ProblemKind.UNREACHABLE, classifyConnectionError(darwin(-1009)).kind)
    }

    @Test
    fun `the note says local network access may be off, with or without another connection`() {
        val local = PlexCandidate("https://a.plex.direct:32400", PlexConnectionKind.LOCAL)
        val remote = PlexCandidate("https://b.plex.direct:32400", PlexConnectionKind.REMOTE)
        val off = ProbeFailure(local, ConnectionProblem(ProblemKind.LOCAL_NETWORK_OFF))
        assertEquals(LOCAL_NETWORK_OFF_NOTE, connectionNote(ConnectionPick(null, listOf(off))))
        assertEquals("$LOCAL_NETWORK_OFF_NOTE; using the remote connection", connectionNote(ConnectionPick(remote, listOf(off))))
    }

    @Test
    fun `an iOS error of another domain or code is not a connection problem`() {
        assertEquals(ProblemKind.OTHER, classifyConnectionError(darwin(-1003, domain = "NSPOSIXErrorDomain")).kind)
        assertEquals(ProblemKind.OTHER, classifyConnectionError(darwin(-1011)).kind)
        assertEquals(ProblemKind.OTHER, classifyConnectionError(DarwinHttpRequestException("Exception in http request")).kind)
    }

    @Test
    fun `problem texts are fixed and hold no address or token`() {
        assertEquals("name not found", ConnectionProblem(ProblemKind.UNKNOWN_HOST).text)
        assertEquals("timeout", ConnectionProblem(ProblemKind.TIMEOUT).text)
        assertEquals("connection refused", ConnectionProblem(ProblemKind.REFUSED).text)
        assertEquals("HTTP 401: token refused", ConnectionProblem(ProblemKind.HTTP, 401).text)
        assertEquals("HTTP 503", ConnectionProblem(ProblemKind.HTTP, 503).text)
    }

    @Test
    fun `a blocked plex direct name that the local address works around is said so`() {
        val pick = ConnectionPick(b, listOf(ProbeFailure(a, ConnectionProblem(ProblemKind.UNKNOWN_HOST))))

        assertEquals("This network blocks the plex.direct name; using the local address", connectionNote(pick))
    }

    @Test
    fun `a blocked plex direct name with no way around is said so`() {
        val pick = ConnectionPick(
            null,
            listOf(
                ProbeFailure(a, ConnectionProblem(ProblemKind.UNKNOWN_HOST)),
                ProbeFailure(b, ConnectionProblem(ProblemKind.TIMEOUT)),
            )
        )

        assertEquals(
            "This network blocks the plex.direct name; the local address did not answer either (timeout)",
            connectionNote(pick)
        )
    }

    @Test
    fun `no answer at all names the first reason`() {
        val pick = ConnectionPick(null, listOf(ProbeFailure(c, ConnectionProblem(ProblemKind.TIMEOUT))))
        assertEquals("Could not reach the server: timeout", connectionNote(pick))
        assertEquals("Could not reach the server: no address to try", connectionNote(ConnectionPick(null, emptyList())))
    }

    @Test
    fun `a clean pick has no note`() {
        assertNull(connectionNote(ConnectionPick(a, emptyList())))
        assertNull(connectionNote(ConnectionPick(c, listOf(ProbeFailure(a, ConnectionProblem(ProblemKind.TIMEOUT))))))
    }

    @Test
    fun `re-probing waits 30 seconds between tries`() {
        assertTrue(reprobeAllowed(lastProbeAtMs = null, nowMs = 0))
        assertFalse(reprobeAllowed(lastProbeAtMs = 1_000, nowMs = 30_999))
        assertTrue(reprobeAllowed(lastProbeAtMs = 1_000, nowMs = 31_000))
        // A clock that went back does not block re-probing for long.
        assertTrue(reprobeAllowed(lastProbeAtMs = 100_000, nowMs = 1_000))
    }

    @Test
    fun `labels for each kind`() {
        assertEquals(listOf("Local", "Local address", "Remote", "Relay", "Saved address"), PlexConnectionKind.entries.map { it.label })
    }
}
