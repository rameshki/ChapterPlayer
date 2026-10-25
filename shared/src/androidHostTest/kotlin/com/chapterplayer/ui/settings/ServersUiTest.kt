package com.chapterplayer.ui.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.sync.ConnectionTest
import com.chapterplayer.data.sync.DataStoreServerStatusStore
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.data.sync.PlexConnectionManager
import com.chapterplayer.data.sync.ProbeFailureInfo
import com.chapterplayer.data.sync.ServerSyncPhase
import com.chapterplayer.data.sync.ServerSyncStatus
import com.chapterplayer.data.sync.StoredCandidate
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.domain.plex.ProbeResult
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServersUiTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val minute = 60_000L
    private val abs = ServerConfig("a1", ServerSource.ABS, "Home ABS", "http://abs.test", "secret-abs")
    private val plex = ServerConfig("p1", ServerSource.PLEX, "Home Plex", "http://192.0.2.11:32400", "secret-plex")

    @Test
    fun `each row has the name, type, connection, status and the actions it allows`() {
        val statuses = mapOf(
            "a1" to ServerSyncStatus("a1", ServerSyncPhase.Idle(10 * minute, 342), "Audiobookshelf address"),
            "p1" to ServerSyncStatus(
                "p1", ServerSyncPhase.Failed("Could not reach the server: timeout", 20 * minute), "Local address",
                connectionNote = "This network blocks the plex.direct name; using the local address",
                probeFailures = listOf(ProbeFailureInfo("https://x.plex.direct:32400", "Local", "name not found")),
            ),
        )

        val rows = serverRows(listOf(abs, plex), statuses, nowMs = 22 * minute)

        assertEquals(listOf("Home ABS", "Home Plex"), rows.map { it.name })
        assertEquals(listOf("Audiobookshelf", "Plex"), rows.map { it.typeLabel })
        assertEquals(listOf("Audiobookshelf address", "Local address"), rows.map { it.connectionLabel })
        assertEquals("Last synced 12 min ago · 342 books", rows[0].statusLine)
        assertEquals("Sync failed: Could not reach the server: timeout", rows[1].statusLine)
        assertEquals(listOf(false, true), rows.map { it.failed })
        assertEquals(listOf(false, true), rows.map { it.canTestConnection })
        assertEquals("This network blocks the plex.direct name; using the local address", rows[1].connectionNote)
        assertTrue(rows.none { it.toString().contains("secret") })
    }

    @Test
    fun `a row without a status yet is not synced, and a syncing row says so`() {
        val rows = serverRows(
            listOf(abs, plex),
            mapOf("p1" to ServerSyncStatus("p1", ServerSyncPhase.Syncing, "Remote")),
            nowMs = 0
        )
        assertEquals("Not synced yet", rows[0].statusLine)
        assertEquals("Syncing…", rows[1].statusLine)
        assertTrue(rows[1].syncing)
        assertFalse(rows[0].syncing)
    }

    @Test
    fun `test results list every connection with ok or the reason`() {
        val local = PlexCandidate("https://192-0-2-11.abc.plex.direct:32400", PlexConnectionKind.LOCAL)
        val raw = PlexCandidate("http://192.0.2.11:32400", PlexConnectionKind.LOCAL_ADDRESS)
        val relay = PlexCandidate("https://198-51-100-200.abc.plex.direct:8443", PlexConnectionKind.RELAY)
        val test = ConnectionTest(
            listOf(
                ProbeResult(local, ConnectionProblem(ProblemKind.UNKNOWN_HOST)),
                ProbeResult(raw, null),
                ProbeResult(relay, ConnectionProblem(ProblemKind.TIMEOUT)),
            ),
            chosen = raw,
            note = "This network blocks the plex.direct name; using the local address",
        )

        val ui = connectionTestUi("Home Plex", test)

        assertEquals(
            listOf(
                ConnectionTestRowUi("Local", local.uri, ok = false, result = "name not found", inUse = false),
                ConnectionTestRowUi("Local address", raw.uri, ok = true, result = "OK", inUse = true),
                ConnectionTestRowUi("Relay", relay.uri, ok = false, result = "timeout", inUse = false),
            ),
            ui.rows
        )
        assertEquals("This network blocks the plex.direct name; using the local address", ui.note)
        assertFalse(ui.running)
        assertEquals("Could not test this server", connectionTestUi("X", null).message)
    }

    @Test
    fun `test connection shows a running dialog, then the results, and switches the address`() {
        val servers = object : com.chapterplayer.data.repository.ServerRepository {
            val flow = kotlinx.coroutines.flow.MutableStateFlow(listOf(plex.copy(baseUrl = "https://192-0-2-11.abc.plex.direct:32400")))
            override fun observeAll() = flow
            override suspend fun getById(id: String) = flow.value.firstOrNull { it.id == id }
            override suspend fun save(config: ServerConfig) { flow.value = listOf(config) }
            override suspend fun delete(id: String) {}
            override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
        }
        val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())
        runBlocking {
            store.update("p1") {
                it.copy(candidates = listOf(
                    StoredCandidate("https://192-0-2-11.abc.plex.direct:32400", "LOCAL"),
                    StoredCandidate("http://192.0.2.11:32400", "LOCAL_ADDRESS"),
                ))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val manager = PlexConnectionManager(
            servers, store, { uri, _ -> if (uri.contains("plex.direct")) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }, { null },
            io = UnconfinedTestDispatcher()
        )
        val coordinator = LibrarySyncCoordinator(FakeBookRepository(), servers, scope, connections = manager, statusStore = store)
        val vm = ServersViewModel(servers, coordinator, scope)

        vm.testConnection("p1")

        val ui = assertNotNull(vm.connectionTest.value)
        assertEquals("Home Plex", ui.serverName)
        assertEquals(listOf(false, true), ui.rows.map { it.ok })
        assertEquals("http://192.0.2.11:32400", servers.flow.value.single().baseUrl)

        vm.dismissConnectionTest()
        assertNull(vm.connectionTest.value)
    }

    @Test
    fun `sync now asks the coordinator for that server`() {
        val synced = mutableListOf<String>()
        val books = object : FakeBookRepository() {
            override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> { synced += server.id; return Result.success(Unit) }
        }
        val servers = FakeServerList(listOf(abs, plex))
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val vm = ServersViewModel(servers, LibrarySyncCoordinator(books, servers, scope), scope)

        vm.syncNow("a1")

        assertEquals(listOf("a1"), synced)
    }
}
