package com.chapterplayer.data.sync

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-server sync status the Servers page shows, and "Sync now". */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySyncCoordinatorStatusTest {

    private class Books : FakeBookRepository() {
        val synced = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var outcome: (ServerConfig) -> Result<Unit> = { Result.success(Unit) }
        override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> {
            synced += server.id
            gate?.await()
            return outcome(server)
        }
    }

    private fun server(id: String) = ServerConfig(id, ServerSource.ABS, "Name $id", "http://$id.test", "t")

    private var now = 1_000L
    private val store = DataStoreServerStatusStore(InMemoryPreferencesDataStore())

    private fun TestScope.coordinator(books: Books, servers: FakeServerList): LibrarySyncCoordinator {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val c = LibrarySyncCoordinator(books, servers, scope, statusStore = store, nowMs = { now })
        // The status is shared while someone watches it, as the Servers page does.
        scope.launch { c.serverStatus.collect {} }
        return c
    }

    @Test
    fun `a successful sync is remembered with the cached book count`() = runTest {
        val books = Books()
        books.books.value = listOf(testBook("x").copy(serverId = "a"), testBook("y").copy(serverId = "a"))
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.syncNow("a")

        val status = c.serverStatus.value.getValue("a")
        assertEquals(ServerSyncPhase.Idle(lastSuccessAtMs = 1_000L, bookCount = 2), status.phase)
        assertEquals("Audiobookshelf address", status.connectionLabel)
        assertEquals(1_000L, store.get("a").lastSuccessAtMs)
    }

    @Test
    fun `a failed sync is remembered with a short reason, and the next success clears it`() = runTest {
        val books = Books()
        books.outcome = { Result.failure(SocketTimeoutException("connect timed out after 30000ms to http://a.test")) }
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.syncNow("a")

        assertEquals(ServerSyncPhase.Failed("Could not reach the server: timeout", 1_000L), c.serverStatus.value.getValue("a").phase)
        assertEquals("Name a: Could not reach the server: timeout", c.state.value.syncError)

        now = 2_000L
        books.outcome = { Result.success(Unit) }
        c.syncNow("a")
        assertEquals(ServerSyncPhase.Idle(2_000L, 0), c.serverStatus.value.getValue("a").phase)
        assertEquals(null, store.get("a").lastErrorText)
    }

    @Test
    fun `a server that needs sign in is marked failed`() = runTest {
        val c = coordinator(Books(), FakeServerList(listOf(server("a").copy(token = ""))))

        c.syncNow("a")

        assertEquals(ServerSyncPhase.Failed("Sign in again in Settings", 1_000L), c.serverStatus.value.getValue("a").phase)
    }

    @Test
    fun `the status says syncing while that server syncs, the others stay idle`() = runTest {
        val books = Books()
        val gate = CompletableDeferred<Unit>()
        books.gate = gate
        val c = coordinator(books, FakeServerList(listOf(server("a"), server("b"))))

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncNow("a") }

        assertEquals(ServerSyncPhase.Syncing, c.serverStatus.value.getValue("a").phase)
        assertEquals(ServerSyncPhase.Idle(null, 0), c.serverStatus.value.getValue("b").phase)
        gate.complete(Unit)
        job.join()
        assertEquals(ServerSyncPhase.Idle(1_000L, 0), c.serverStatus.value.getValue("a").phase)
    }

    @Test
    fun `sync now syncs only that server, and while it runs one follow-up is queued`() = runTest {
        val books = Books()
        val gate = CompletableDeferred<Unit>()
        books.gate = gate
        val c = coordinator(books, FakeServerList(listOf(server("a"), server("b"))))

        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncNow("a") }
        c.syncNow("a")
        c.syncNow("a")
        assertEquals(listOf("a"), books.synced)

        gate.complete(Unit)
        first.join()
        assertEquals(listOf("a", "a"), books.synced)
    }

    @Test
    fun `sync now of an unknown server does nothing`() = runTest {
        val books = Books()
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.syncNow("gone")

        assertEquals(emptyList(), books.synced)
    }
}
