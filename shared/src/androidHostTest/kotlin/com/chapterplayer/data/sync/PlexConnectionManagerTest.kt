package com.chapterplayer.data.sync

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.domain.plex.ProblemKind
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

    private val local = PlexCandidate("https://192-0-2-11.abc.plex.direct:32400", PlexConnectionKind.LOCAL)
    private val raw = PlexCandidate("http://192.0.2.11:32400", PlexConnectionKind.LOCAL_ADDRESS)
    private val remote = PlexCandidate("https://198-51-100-1.abc.plex.direct:32400", PlexConnectionKind.REMOTE)

    private fun plex(baseUrl: String = local.uri, token: String = "plex-token") =
        ServerConfig("p1", ServerSource.PLEX, "Home Plex", baseUrl, token)

    private var now = 100_000L
    private val probed = mutableListOf<Pair<String, String>>()
    private var answers: (String) -> ConnectionProblem? = { null }
    private var lookedUp = 0
    private var lookupResult: List<PlexCandidate>? = null

    private val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())

    private fun manager(servers: ServerRepository) = PlexConnectionManager(
        servers = servers,
        store = store,
        probe = { uri, token -> probed += uri to token; answers(uri) },
        lookup = { lookedUp++; lookupResult },
        nowMs = { now },
    )

    private suspend fun remembered(vararg candidates: PlexCandidate, chosen: PlexCandidate? = null, probedAt: Long? = null) =
        store.update("p1") { it.copy(candidates = candidates.map(StoredCandidate::of), chosenUri = chosen?.uri, lastProbeAtMs = probedAt) }

    @Test
    fun `remembering a new server stores its connections and the one chosen`() = runTest {
        val m = manager(SavingServers(listOf(plex())))

        m.remember("p1", listOf(local, raw, remote), local)

        val record = store.get("p1")
        assertEquals(listOf(local, raw, remote), record.candidates.map { it.toCandidate() })
        assertEquals(local.uri, record.chosenUri)
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
        assertEquals("This network blocks the plex.direct name; using the local address", record.probeNote)
        assertEquals(listOf(StoredProbeFailure(local.uri, "LOCAL", "name not found")), record.probeFailures)
        // Every probe carried the server's token, as a parameter for the header.
        assertTrue(probed.all { it.second == "plex-token" })
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
        answers = { ConnectionProblem(ProblemKind.TIMEOUT) }
        val m = manager(servers)

        val pick = assertNotNull(m.reprobe("p1"))

        assertNull(pick.winner)
        assertEquals(local.uri, servers.getById("p1")!!.baseUrl)
        assertTrue(servers.saved.isEmpty())
        assertEquals("Could not reach the server: timeout", store.get("p1").probeNote)
        assertEquals(local.uri, store.get("p1").chosenUri)
    }

    @Test
    fun `a server saved before connections were kept asks plex tv for them`() = runTest {
        val servers = SavingServers(listOf(plex()))
        lookupResult = listOf(local, raw, remote)
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }
        val m = manager(servers)

        m.reprobe("p1")

        assertEquals(1, lookedUp)
        assertEquals(listOf(local, raw, remote), store.get("p1").candidates.map { it.toCandidate() })
        assertEquals(raw.uri, servers.getById("p1")!!.baseUrl)
    }

    @Test
    fun `without a list from plex tv the saved address is probed alone`() = runTest {
        val m = manager(SavingServers(listOf(plex())))

        val pick = assertNotNull(m.reprobe("p1"))

        assertEquals(PlexCandidate(local.uri, PlexConnectionKind.SAVED), pick.winner)
        assertEquals(listOf(local.uri), probed.map { it.first })
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
    }

    @Test
    fun `a failure with no connection that answers gives the reason`() = runTest {
        remembered(local, remote, chosen = local)
        answers = { ConnectionProblem(ProblemKind.TIMEOUT) }
        val m = manager(SavingServers(listOf(plex())))

        val outcome = m.afterFailure(plex(), UnknownHostException("x"), force = false)

        assertNull(outcome.retryWith)
        assertEquals("Could not reach the server: timeout", outcome.message)
    }

    @Test
    fun `before a sync a server with no chosen connection is probed once`() = runTest {
        val servers = SavingServers(listOf(plex()))
        lookupResult = listOf(local, remote)
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.TIMEOUT) else null }
        val m = manager(servers)

        val fresh = m.beforeSync(plex())
        assertEquals(remote.uri, fresh.baseUrl)
        val count = probed.size

        m.beforeSync(servers.getById("p1")!!)
        assertEquals(count, probed.size)
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
        lookupResult = listOf(local, raw, remote)
        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }
        val m = manager(servers)

        val test = assertNotNull(m.testConnection("p1"))

        assertEquals(listOf(local, raw, remote), test.results.map { it.candidate })
        assertEquals(listOf(false, true, true), test.results.map { it.problem == null })
        assertEquals(raw, test.chosen)
        assertEquals(raw.uri, servers.getById("p1")!!.baseUrl)
        assertEquals("This network blocks the plex.direct name; using the local address", test.note)
    }

    private fun TestScope.appScope() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `a network change re-probes, waiting out the 30 seconds when needed`() = runTest {
        val servers = SavingServers(listOf(plex()))
        remembered(local, remote, chosen = local, probedAt = now)
        val network = MutableStateFlow<String?>("wifi")
        // The test clock drives the throttle window.
        val start = now
        val timed = PlexConnectionManager(
            servers, store, { uri, token -> probed += uri to token; answers(uri) }, { lookupResult },
            nowMs = { start + testScheduler.currentTime },
        )

        timed.watchNetwork(appScope(), network)
        runCurrent()
        assertTrue(probed.isEmpty())

        answers = { uri -> if (uri == local.uri) ConnectionProblem(ProblemKind.TIMEOUT) else null }
        network.value = "cellular"
        advanceTimeBy(29_000)
        assertTrue(probed.isEmpty())
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(probed.isNotEmpty())
        assertEquals(remote.uri, servers.getById("p1")!!.baseUrl)
    }
}
