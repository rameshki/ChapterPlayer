package com.chapterplayer.data.sync

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.domain.plex.plexTokenFor
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlexConnectionManagerTest {

    /** Saves like the real repository, so a switched address shows in the next read. */
    private class SavingServers(initial: List<ServerConfig>) : ServerRepository {
        val flow = MutableStateFlow(initial)
        val saved = mutableListOf<ServerConfig>()
        override fun observeAll(): Flow<List<ServerConfig>> = flow
        override suspend fun getById(id: String) = flow.value.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) {
            saved += config
            flow.value = flow.value.filter { it.id != config.id } + config
        }
        override suspend fun delete(id: String) { flow.value = flow.value.filter { it.id != id } }
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    private val machine = "fixturemachine01"
    private val local = PlexCandidate("https://192-0-2-11.abc.plex.direct:32400", PlexConnectionKind.LOCAL)
    private val raw = PlexCandidate("http://192.0.2.11:32400", PlexConnectionKind.LOCAL_ADDRESS)
    private val remote = PlexCandidate("https://198-51-100-1.abc.plex.direct:32400", PlexConnectionKind.REMOTE)

    private fun plex(baseUrl: String = local.uri, token: String = "plex-token") =
        ServerConfig("p1", ServerSource.PLEX, "Home Plex", baseUrl, token)

    private var now = 100_000L
    private val probed = mutableListOf<Pair<String, String?>>()
    private var answers: (String) -> ConnectionProblem? = { null }
    private var lookedUp = 0
    private var lookupResult: PlexServerConnections? = null
    private val switches = mutableListOf<String>()

    private val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())

    private fun TestScope.manager(servers: ServerRepository, nowMs: () -> Long = { now }) = PlexConnectionManager(
        servers = servers,
        store = store,
        probe = { uri, machineId -> probed += uri to machineId; answers(uri) },
        lookup = { lookedUp++; lookupResult },
        nowMs = nowMs,
        io = UnconfinedTestDispatcher(testScheduler),
    ).also { it.onSwitched = { id -> switches += id } }

    private suspend fun remembered(
        vararg candidates: PlexCandidate,
        chosen: PlexCandidate? = null,
        probedAt: Long? = null,
        verified: List<String> = listOfNotNull(chosen?.uri),
    ) = store.update("p1") {
        it.copy(
            candidates = candidates.map(StoredCandidate::of), machineId = machine, chosenUri = chosen?.uri,
            lastProbeAtMs = probedAt, verifiedUris = verified,
        )
    }

    @Test
    fun `remembering a new server stores its connections, machine id and the one chosen`() = runTest {
        val m = manager(SavingServers(listOf(plex())))

        m.remember("p1", machine, listOf(local, raw, remote), local)

        val record = store.get("p1")
        assertEquals(listOf(local, raw, remote), record.candidates.map { it.toCandidate() })
        assertEquals(machine, record.machineId)
        assertEquals(local.uri, record.chosenUri)
        assertEquals(listOf(local.uri), record.verifiedUris)
        assertEquals(now, record.lastProbeAtMs)
    }

    @Test
    fun `a blocked plex direct name switches the server to the local address`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, raw, remote, chosen = local)
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }
        val m = manager(servers)

        val pick = assertNotNull(m.reprobe("p1"))

        assertEquals(raw, pick.winner)
        assertEquals(raw.uri, servers.getById("p1")!!.baseUrl)
        assertEquals("plex-token", servers.saved.single().token)
        val record = store.get("p1")
        assertEquals(raw.uri, record.chosenUri)
        assertEquals(listOf(local.uri, raw.uri), record.verifiedUris)
        assertEquals("This network blocks the plex.direct name; using the local address", record.probeNote)
        assertEquals(listOf(StoredProbeFailure(local.uri, "LOCAL", "name not found")), record.probeFailures)
        // Every probe asked for this server by its machine id; no probe is given the token.
        assertTrue(probed.all { it.second == machine })
        assertEquals(listOf("p1"), switches)
    }

    @Test
    fun `another device answering on an address does not win`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, raw, remote, chosen = local)
        answers = { uri -> if (uri == remote.uri) null else ConnectionProblem(ProblemKind.WRONG_SERVER) }
        val m = manager(servers)

        assertEquals(remote, m.reprobe("p1")?.winner)
        assertEquals(remote.uri, servers.getById("p1")!!.baseUrl)
        assertFalse(raw.uri in store.get("p1").verifiedUris)
    }

    @Test
    fun `re-probing is throttled to once per 30 seconds unless forced`() = runTest {
        remembered(local, chosen = local, probedAt = now - 10_000)
        val m = manager(SavingServers(listOf(plex())))

        assertNull(m.reprobe("p1"))
        assertEquals(0, probed.size)

        assertNotNull(m.reprobe("p1", force = true))
        assertEquals(1, probed.size)

        now += 30_000
        assertNotNull(m.reprobe("p1"))
        assertEquals(2, probed.size)
    }

    @Test
    fun `the address stays when nothing answers, and the note says why`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, remote, chosen = local)
        answers = { ConnectionProblem(ProblemKind.REFUSED) }
        val m = manager(servers)

        val pick = assertNotNull(m.reprobe("p1"))

        assertNull(pick.winner)
        assertEquals(local.uri, servers.getById("p1")!!.baseUrl)
        assertTrue(servers.saved.isEmpty())
        assertEquals("Could not reach the server: connection refused", store.get("p1").probeNote)
        assertTrue(store.get("p1").probeFailed)
        assertEquals(local.uri, store.get("p1").chosenUri)
    }

    @Test
    fun `a server saved before connections were kept asks plex tv for them`() = runTest {
        val servers = SavingServers(listOf(plex()))
        lookupResult = PlexServerConnections(machine, listOf(local, raw, remote))
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }
        val m = manager(servers)

        m.reprobe("p1")

        assertEquals(1, lookedUp)
        assertEquals(listOf(local, raw, remote), store.get("p1").candidates.map { it.toCandidate() })
        assertEquals(machine, store.get("p1").machineId)
        assertEquals(raw.uri, servers.getById("p1")!!.baseUrl)
    }

    @Test
    fun `without a list from plex tv the saved address is probed alone and nothing is verified`() = runTest {
        val m = manager(SavingServers(listOf(plex())))

        val pick = assertNotNull(m.reprobe("p1"))

        assertEquals(PlexCandidate(local.uri, PlexConnectionKind.SAVED), pick.winner)
        assertEquals(listOf<Pair<String, String?>>(local.uri to null), probed)
        assertEquals(emptyList<String>(), store.get("p1").verifiedUris)
    }

    @Test
    fun `only connection failures re-probe and give a server to retry with`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, raw, chosen = local)
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }
        val m = manager(servers)

        val other = m.afterFailure(plex(), IllegalStateException("HTTP 500"), force = false)
        assertNull(other.retryWith)
        assertEquals(0, probed.size)

        val outcome = m.afterFailure(plex(), UnknownHostException("x"), force = false)
        assertEquals(raw.uri, outcome.retryWith?.baseUrl)
        // The caller retries itself: no extra sync is asked for.
        assertEquals(emptyList<String>(), switches)
    }

    @Test
    fun `a failure on an address that was already replaced retries on the new one without probing`() = runTest {
        val servers = SavingServers(listOf(plex(baseUrl = raw.uri)))
        remembered(local, raw, chosen = raw)
        val m = manager(servers)

        val outcome = m.afterFailure(plex(baseUrl = local.uri + "/"), UnknownHostException("x"), force = true)

        assertEquals(raw.uri, outcome.retryWith?.baseUrl)
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `a failure with no connection that answers gives the reason`() = runTest {
        remembered(local, remote, chosen = local)
        answers = { ConnectionProblem(ProblemKind.REFUSED) }
        val m = manager(SavingServers(listOf(plex())))

        val outcome = m.afterFailure(plex(), UnknownHostException("x"), force = false)

        assertNull(outcome.retryWith)
        assertEquals("Could not reach the server: connection refused", outcome.message)
    }

    @Test
    fun `before a sync a server with no chosen connection is probed once`() = runTest {
        val servers = SavingServers(listOf(plex()))
        lookupResult = PlexServerConnections(machine, listOf(local, remote))
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.REFUSED) else null }
        val m = manager(servers)

        val fresh = m.beforeSync(plex())
        assertEquals(remote.uri, fresh.baseUrl)
        val count = probed.size

        m.beforeSync(servers.getById("p1")!!)
        assertEquals(count, probed.size)
    }

    @Test
    fun `before a sync a plain address not verified on this network is checked, and left for https when it fails`() = runTest {
        val servers = SavingServers(listOf(plex(baseUrl = raw.uri)))
        remembered(local, raw, remote, chosen = raw, probedAt = now, verified = listOf(local.uri))
        answers = { uri -> if (uri == remote.uri) null else ConnectionProblem(ProblemKind.REFUSED) }
        val m = manager(servers)

        val fresh = m.beforeSync(servers.getById("p1")!!)

        assertTrue(probed.isNotEmpty()) // despite the 30 s window
        assertEquals(remote.uri, fresh.baseUrl)
    }

    @Test
    fun `audiobookshelf servers and signed out servers are never probed`() = runTest {
        val abs = ServerConfig("a1", ServerSource.ABS, "ABS", "http://abs.test", "t")
        val out = plex(token = "")
        val m = manager(SavingServers(listOf(abs, out)))

        assertNull(m.reprobe("a1"))
        assertNull(m.reprobe("p1"))
        assertEquals(abs, m.beforeSync(abs))
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `testing a connection probes every candidate with a fresh list and switches to the best`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, chosen = local)
        lookupResult = PlexServerConnections(machine, listOf(local, raw, remote))
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }
        val m = manager(servers)

        val test = assertNotNull(m.testConnection("p1"))

        assertEquals(listOf(local, raw, remote), test.results.map { it.candidate })
        assertEquals(listOf(false, true, true), test.results.map { it.problem == null })
        assertEquals(raw, test.chosen)
        assertEquals(raw.uri, servers.getById("p1")!!.baseUrl)
        assertEquals("This network blocks the plex.direct name; using the local address", test.note)
        assertEquals(setOf(local.uri, raw.uri, remote.uri), store.get("p1").verifiedUris.toSet())
        assertEquals(listOf("p1"), switches)
    }

    @Test
    fun `forgetting clears the connections`() = runTest {
        remembered(local, raw, chosen = raw)
        val m = manager(SavingServers(listOf(plex())))

        m.forget("p1")

        val record = store.get("p1")
        assertTrue(record.candidates.isEmpty())
        assertNull(record.chosenUri)
        assertNull(record.machineId)
        assertEquals(emptyList<String>(), record.verifiedUris)
    }

    private fun TestScope.appScope() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `a network change re-probes, waiting out the 30 seconds when needed`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, remote, chosen = local, probedAt = now)
        val network = MutableStateFlow<String?>("wifi")
        val start = now
        val m = manager(servers, nowMs = { start + testScheduler.currentTime })

        m.watchNetwork(appScope(), network)
        runCurrent()
        assertTrue(probed.isEmpty())

        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.REFUSED) else null }
        network.value = "cellular"
        advanceTimeBy(29_000)
        assertTrue(probed.isEmpty())
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(probed.isNotEmpty())
        assertEquals(remote.uri, servers.getById("p1")!!.baseUrl)
    }

    @Test
    fun `on a new network a plain LAN address loses the token at once and is checked before it gets it back`() = runTest {
        // Verified at home: the raw LAN address is in use and gets the token.
        val servers = SavingServers(listOf(plex(baseUrl = raw.uri)))
        remembered(local, raw, remote, chosen = raw, probedAt = now, verified = listOf(local.uri, raw.uri))
        val network = MutableStateFlow<String?>("hotel-wifi")
        answers = { ConnectionProblem(ProblemKind.REFUSED) }
        val m = manager(servers)

        m.watchNetwork(appScope(), network)
        runCurrent()

        // Elsewhere, nothing answers: the server is back on its https name, and the LAN address is not trusted.
        val current = servers.getById("p1")!!.copy(alternateBaseUrls = store.get("p1").verifiedUris)
        assertEquals(local.uri, current.baseUrl)
        assertEquals(listOf(local.uri), current.alternateBaseUrls)
        assertNull(plexTokenFor("http://192.0.2.11:32400/library/metadata/1/thumb/2", listOf(current)))
    }

    @Test
    fun `after a remote pick the local connections are tried again a minute later`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, remote, chosen = local)
        val network = MutableStateFlow<String?>("wifi")
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.REFUSED) else null }
        val start = now
        val m = manager(servers, nowMs = { start + testScheduler.currentTime })

        m.watchNetwork(appScope(), network)
        runCurrent()
        assertEquals(remote.uri, servers.getById("p1")!!.baseUrl)

        // The home network was slow to answer; a minute later it does.
        answers = { null }
        advanceTimeBy(LOCAL_RETRY_DELAY_MS + 100)
        runCurrent()
        assertEquals(local.uri, servers.getById("p1")!!.baseUrl)
        assertEquals(listOf("p1", "p1"), switches)
    }
}
