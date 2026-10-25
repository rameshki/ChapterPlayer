package com.chapterplayer.data.sync

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.PlexCandidate
import com.chapterplayer.domain.plex.PlexConnectionKind
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sync and the Plex connection choice: a connection failure re-probes and retries once on the new address. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySyncCoordinatorConnectionTest {

    private class SavingServers(initial: List<ServerConfig>) : ServerRepository {
        val flow = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<ServerConfig>> = flow
        override suspend fun getById(id: String) = flow.value.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) { flow.value = flow.value.filter { it.id != config.id } + config }
        override suspend fun delete(id: String) { flow.value = flow.value.filter { it.id != id } }
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    /** Fails with an unknown host on any plex.direct address, like a router that blocks those names. */
    private class BlockedNameBooks : FakeBookRepository() {
        val syncedWith = mutableListOf<String>()
        override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> {
            syncedWith += server.baseUrl
            return if (server.baseUrl.contains("plex.direct")) Result.failure(UnknownHostException("blocked")) else Result.success(Unit)
        }
    }

    private val local = PlexCandidate("https://192-0-2-11.abc.plex.direct:32400", PlexConnectionKind.LOCAL)
    private val raw = PlexCandidate("http://192.0.2.11:32400", PlexConnectionKind.LOCAL_ADDRESS)
    private val plex = ServerConfig("p1", ServerSource.PLEX, "Home Plex", local.uri, "tok")

    private val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())
    private var answers: (String) -> ConnectionProblem? = { uri -> if (uri.contains("plex.direct")) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else null }

    private fun TestScope.coordinator(books: FakeBookRepository, servers: ServerRepository) = LibrarySyncCoordinator(
        books, servers, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        connections = PlexConnectionManager(servers, store, { uri, _ -> answers(uri) }, { null }, nowMs = { 1_000_000L }, io = UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun `a blocked plex direct name is worked around and the sync succeeds`() = runTest {
        // Chosen at add time, at home on a network that allowed the name.
        store.update("p1") {
            it.copy(candidates = listOf(local, raw).map(StoredCandidate::of), machineId = "m1", chosenUri = local.uri, verifiedUris = listOf(local.uri), lastProbeAtMs = 0)
        }
        val servers = SavingServers(listOf(plex))
        val books = BlockedNameBooks()
        val c = coordinator(books, servers)

        c.syncNowAll()

        assertEquals(listOf(local.uri, raw.uri), books.syncedWith)
        assertEquals(raw.uri, servers.getById("p1")!!.baseUrl)
        assertNull(c.state.value.syncError)
    }

    @Test
    fun `with nothing that answers the error says why`() = runTest {
        store.update("p1") { it.copy(candidates = listOf(local, raw).map(StoredCandidate::of), chosenUri = local.uri, lastProbeAtMs = 0) }
        answers = { uri -> if (uri.contains("plex.direct")) ConnectionProblem(ProblemKind.UNKNOWN_HOST) else ConnectionProblem(ProblemKind.TIMEOUT) }
        val books = BlockedNameBooks()
        val c = coordinator(books, SavingServers(listOf(plex)))

        c.syncNowAll()

        assertEquals(listOf(local.uri), books.syncedWith)
        assertEquals(
            "Home Plex: This network blocks the plex.direct name; the local address did not answer either (timeout)",
            c.state.value.syncError
        )
    }

    @Test
    fun `a sync that loses the connection retries itself and asks for no extra sync`() = runTest {
        store.update("p1") { it.copy(candidates = listOf(local, raw).map(StoredCandidate::of), machineId = "m1", chosenUri = local.uri, verifiedUris = listOf(local.uri)) }
        val servers = SavingServers(listOf(plex))
        val books = BlockedNameBooks()
        val manager = PlexConnectionManager(servers, store, { uri, _ -> answers(uri) }, { null }, nowMs = { 1_000_000L }, io = UnconfinedTestDispatcher(testScheduler))
        val c = LibrarySyncCoordinator(
            books, servers, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), connections = manager
        )
        manager.onSwitched = { c.requestSync(it) }

        c.syncNow("p1")

        assertEquals(listOf(local.uri, raw.uri), books.syncedWith)
    }

    @Test
    fun `a successful sync clears the note of a failed probe`() = runTest {
        store.update("p1") { it.copy(probeNote = "Could not reach the server: timeout", probeFailed = true) }
        val servers = SavingServers(listOf(plex.copy(baseUrl = raw.uri)))
        val c = LibrarySyncCoordinator(
            BlockedNameBooks(), servers, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            statusStore = store
        )

        c.syncNow("p1")

        assertNull(store.get("p1").probeNote)
    }

    @Test
    fun `a server added before connections were kept is probed before its first sync`() = runTest {
        val servers = SavingServers(listOf(plex))
        val books = BlockedNameBooks()
        val c = LibrarySyncCoordinator(
            books, servers, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            connections = PlexConnectionManager(
                servers, store, { uri, _ -> answers(uri) }, { PlexServerConnections("m1", listOf(local, raw)) }, nowMs = { 1_000_000L },
                io = UnconfinedTestDispatcher(testScheduler)
            ),
        )

        c.start()

        assertEquals(listOf(raw.uri), books.syncedWith)
    }
}
