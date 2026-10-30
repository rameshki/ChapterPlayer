package com.chapterplayer.data.sync

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySyncCoordinatorTest {

    private open class RecordingBooks(
        var outcome: (ServerConfig) -> Result<Unit> = { Result.success(Unit) },
        var gate: CompletableDeferred<Unit>? = null
    ) : FakeBookRepository() {
        val synced = mutableListOf<String>()
        override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> {
            synced += server.id
            gate?.await()
            return outcome(server)
        }
    }

    private fun server(id: String, token: String = "t") =
        ServerConfig(id, ServerSource.ABS, "Name $id", "http://$id.test", token)

    private fun TestScope.coordinator(books: RecordingBooks, servers: FakeServerList) =
        LibrarySyncCoordinator(books, servers, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `starting syncs the servers that have no cached books`() = runTest {
        val books = RecordingBooks()
        books.books.value = listOf(testBook("x").copy(serverId = "cached"))
        val c = coordinator(books, FakeServerList(listOf(server("cached"), server("fresh"))))

        c.start()

        assertEquals(listOf("fresh"), books.synced)
    }

    @Test
    fun `starting twice does not sync twice`() = runTest {
        val books = RecordingBooks()
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.start()
        c.start()

        assertEquals(listOf("a"), books.synced)
    }

    @Test
    fun `adding a server syncs it`() = runTest {
        val books = RecordingBooks()
        books.books.value = listOf(testBook("x").copy(serverId = "a"))
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)
        c.start()
        assertEquals(emptyList(), books.synced)

        servers.flow.value = listOf(server("a"), server("b"))

        assertEquals(listOf("b"), books.synced)
    }

    @Test
    fun `a server needing sign-in is skipped without a network call and says so`() = runTest {
        val books = RecordingBooks()
        val c = coordinator(books, FakeServerList(listOf(server("a", token = ""), server("b"))))

        c.start()

        assertEquals(listOf("b"), books.synced)
        val s = c.state.value
        assertEquals("Name a: Sign in again in Settings", s.syncError)
        assertEquals(listOf("Name a"), s.signInRequiredNames)
        assertFalse(s.allServersNeedSignIn)
    }

    @Test
    fun `failures reach the state and can be dismissed`() = runTest {
        val books = RecordingBooks(outcome = { Result.failure(IllegalStateException("boom")) })
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.start()
        assertEquals("Name a: boom", c.state.value.syncError)

        c.dismissError()
        assertEquals(null, c.state.value.syncError)
    }

    @Test
    fun `a rejected sign-in uses the plain message`() = runTest {
        val books = RecordingBooks(outcome = { Result.failure(SignInRequiredException("other")) })
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.start()

        assertEquals("Name a: Sign in again in Settings", c.state.value.syncError)
    }

    @Test
    fun `the state says syncing while a sync runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.start()
        assertTrue(c.state.value.isSyncing)

        gate.complete(Unit)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `a server is not synced twice at once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        c.start()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncNowAll() }

        assertEquals(listOf("a"), books.synced)

        gate.complete(Unit)
        // the refresh that found it busy is not dropped: it runs once after the first sync
        assertEquals(listOf("a", "a"), books.synced)
        c.syncNowAll()
        assertEquals(listOf("a", "a", "a"), books.synced)
    }

    @Test
    fun `refreshing syncs every server even when books are cached`() = runTest {
        val books = RecordingBooks()
        books.books.value = listOf(testBook("x"))
        val c = coordinator(books, FakeServerList(listOf(server("s1"), server("s2"))))

        c.syncNowAll()

        assertEquals(listOf("s1", "s2"), books.synced)
    }

    @Test
    fun `a foreground pass syncs every server, keeps earlier messages and leaves a busy server to its run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        books.books.value = listOf(testBook("x"))
        val c = coordinator(books, FakeServerList(listOf(server("s1"), server("s2"))))

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncAll() }
        assertEquals(listOf("s1"), books.synced)
        // s1 is still syncing: a second pass does not queue another run of it (only the user's refresh does).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncAll() }
        gate.complete(Unit)
        assertEquals(listOf("s1", "s2"), books.synced)
        c.syncAll()
        assertEquals(listOf("s1", "s2", "s1", "s2"), books.synced)
    }

    @Test
    fun `errors of overlapping runs add up`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(outcome = { Result.failure(IllegalStateException("boom")) }, gate = gate)
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)
        c.start()
        servers.flow.value = listOf(server("a"), server("b"))

        gate.complete(Unit)

        assertEquals("Name a: boom" + "\n" + "Name b: boom", c.state.value.syncError)
    }

    @Test
    fun `a later run does not wipe an earlier error`() = runTest {
        val books = RecordingBooks(outcome = { Result.failure(IllegalStateException("boom ${it.id}")) })
        books.books.value = listOf(testBook("x").copy(serverId = "a")) // a is cached, so only b syncs in the second run
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)
        c.syncNowAll()
        assertEquals("Name a: boom a", c.state.value.syncError)

        servers.flow.value = listOf(server("a"), server("b"))
        c.start() // a second, separate run: b fails too

        assertEquals("Name a: boom a" + "\n" + "Name b: boom b", c.state.value.syncError)
    }

    @Test
    fun `a failure while the starting sync looks at the cache becomes a message and the collector keeps working`() = runTest {
        var broken = true
        val books = object : RecordingBooks() {
            override fun observeAllBooks() =
                if (broken) flow<List<AudioBook>> { throw IllegalStateException("cache unreadable") } else super.observeAllBooks()
        }
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)

        c.start()
        assertEquals("Sync failed: cache unreadable", c.state.value.syncError)

        broken = false
        servers.flow.value = listOf(server("a"), server("b"))
        assertEquals(listOf("a", "b"), books.synced)
    }

    @Test
    fun `a failing server list is reported and the coordinator stays alive`() = runTest {
        val books = RecordingBooks()
        val list = FakeServerList(listOf(server("a")))
        val broken = object : ServerRepository by list {
            override fun observeAll(): Flow<List<ServerConfig>> = flow { throw IllegalStateException("db closed") }
        }
        val c = LibrarySyncCoordinator(books, broken, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

        c.start()

        assertEquals("Could not read the server list", c.state.value.syncError)
        c.requestSync("a")
        assertEquals(listOf("a"), books.synced)
    }

    @Test
    fun `a refresh asked for a server that is syncing runs once more after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        c.start()
        assertEquals(listOf("a"), books.synced)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncNowAll() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncNowAll() }
        assertEquals(listOf("a"), books.synced) // not in parallel

        books.gate = null
        gate.complete(Unit)

        assertEquals(listOf("a", "a"), books.synced) // exactly one follow-up
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `a refresh asked by the user starts with a clean message`() = runTest {
        val books = RecordingBooks(outcome = { Result.failure(IllegalStateException("boom")) })
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        c.start()
        assertEquals("Name a: boom", c.state.value.syncError)

        books.outcome = { Result.success(Unit) }
        c.syncNowAll()

        assertEquals(null, c.state.value.syncError)
    }

    @Test
    fun `a repository that throws becomes a message and the collector keeps working`() = runTest {
        val books = RecordingBooks(outcome = { if (it.id == "a") throw IllegalStateException("kaboom") else Result.success(Unit) })
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)

        c.start()
        assertEquals("Name a: kaboom", c.state.value.syncError)
        assertFalse(c.state.value.isSyncing)

        servers.flow.value = listOf(server("a"), server("b"))
        assertEquals("b", books.synced.last())
    }

    @Test
    fun `a refresh with a throwing repository reports instead of crashing`() = runTest {
        val books = RecordingBooks(outcome = { throw IllegalStateException("kaboom") })
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.syncNowAll()

        assertEquals("Name a: kaboom", c.state.value.syncError)
    }

    @Test
    fun `a server deleted while its sync runs has its rows removed again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)
        c.start()
        assertTrue(c.state.value.isSyncing)

        servers.flow.value = emptyList() // the user deleted it while the sync was running
        gate.complete(Unit)

        assertEquals(listOf("a"), servers.deleted)
    }

    @Test
    fun `a server that still exists after its sync is not deleted`() = runTest {
        val books = RecordingBooks()
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)

        c.start()

        assertEquals(emptyList(), servers.deleted)
    }

    @Test
    fun `a request while idle syncs the server once`() = runTest {
        val books = RecordingBooks()
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.requestSync("a")

        assertEquals(listOf("a"), books.synced)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `a request during a running sync causes exactly one follow-up after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        c.requestSync("a")
        assertEquals(listOf("a"), books.synced)

        c.requestSync("a")
        assertEquals(listOf("a"), books.synced) // not started in parallel

        books.gate = null
        gate.complete(Unit)

        assertEquals(listOf("a", "a"), books.synced)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `three requests during one run still cause exactly one follow-up`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        c.requestSync("a")

        c.requestSync("a")
        c.requestSync("a")
        c.requestSync("a")
        books.gate = null
        gate.complete(Unit)

        assertEquals(listOf("a", "a"), books.synced)
    }

    @Test
    fun `the follow-up reads the server again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val servers = FakeServerList(listOf(server("a")))
        val seen = mutableListOf<List<String>>()
        val c = coordinator(books, servers)
        c.requestSync("a")
        c.requestSync("a")
        servers.flow.value = listOf(server("a").copy(excludedLibraryIds = setOf("lib1")))
        books.outcome = { seen += it.excludedLibraryIds.toList(); Result.success(Unit) }
        books.gate = null
        gate.complete(Unit)

        assertEquals(listOf(listOf("lib1")), seen.takeLast(1))
    }

    @Test
    fun `a failing sync does not block the next request`() = runTest {
        val books = RecordingBooks(outcome = { Result.failure(IllegalStateException("boom")) })
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        c.requestSync("a")
        assertEquals("Name a: boom", c.state.value.syncError)

        books.outcome = { Result.success(Unit) }
        c.requestSync("a")

        assertEquals(listOf("a", "a"), books.synced)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `a repository that throws in a run and its follow-up leaves nothing stuck`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(outcome = { throw IllegalStateException("kaboom") }, gate = gate)
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        c.requestSync("a")
        c.requestSync("a")
        books.gate = null
        gate.complete(Unit)
        assertFalse(c.state.value.isSyncing)

        books.outcome = { Result.success(Unit) }
        c.requestSync("a")

        assertEquals(3, books.synced.size)
    }

    @Test
    fun `a request for a deleted server syncs nothing`() = runTest {
        val books = RecordingBooks()
        val c = coordinator(books, FakeServerList(emptyList()))

        c.requestSync("gone")

        assertEquals(emptyList(), books.synced)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `a server deleted before the follow-up is not synced again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val servers = FakeServerList(listOf(server("a")))
        val c = coordinator(books, servers)
        c.requestSync("a")
        c.requestSync("a")

        servers.flow.value = emptyList()
        gate.complete(Unit)

        assertEquals(listOf("a"), books.synced)
        // and a later request is not blocked by a stuck marker
        servers.flow.value = listOf(server("a"))
        c.requestSync("a")
        assertEquals(listOf("a", "a"), books.synced)
    }

    @Test
    fun `a request queued behind a cancelled sync is still synced`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val c = coordinator(books, FakeServerList(listOf(server("a"))))
        val refresh = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncNowAll() }
        assertEquals(listOf("a"), books.synced)
        c.requestSync("a") // queued behind the running refresh
        books.gate = null

        refresh.cancel() // the screen that asked for the refresh is gone

        assertEquals(listOf("a", "a"), books.synced)
        assertFalse(c.state.value.isSyncing)
        c.requestSync("a") // later requests still work
        assertEquals(listOf("a", "a", "a"), books.synced)
    }

    @Test
    fun `isSyncing stays true while the follow-up looks the server up`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lookup = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val list = FakeServerList(listOf(server("a")))
        var lookups = 0
        val slow = object : com.chapterplayer.data.repository.ServerRepository by list {
            override suspend fun getById(id: String): ServerConfig? {
                // 1: the request, 2: the deleted-server check after the run, 3: the follow-up
                if (++lookups == 3) lookup.await()
                return list.getById(id)
            }
        }
        val c = LibrarySyncCoordinator(books, slow, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        c.requestSync("a")
        c.requestSync("a")
        books.gate = null
        gate.complete(Unit) // the first run ends; the follow-up is now waiting in its lookup

        assertTrue(c.state.value.isSyncing)

        lookup.complete(Unit)
        assertEquals(2, books.synced.size)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `a request queued behind a sync cancelled during its follow-up lookup is still synced`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lookup = CompletableDeferred<Unit>()
        val books = RecordingBooks(gate = gate)
        val list = FakeServerList(listOf(server("a")))
        var lookups = 0
        val slow = object : com.chapterplayer.data.repository.ServerRepository by list {
            override suspend fun getById(id: String): ServerConfig? {
                // 1: the deleted-server check after the run, 2: the follow-up lookup
                if (++lookups == 2) lookup.await()
                return list.getById(id)
            }
        }
        val c = LibrarySyncCoordinator(books, slow, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        val refresh = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.syncNowAll() }
        c.requestSync("a") // queued behind the refresh
        books.gate = null
        gate.complete(Unit) // the run ends; the follow-up lookup is now blocked

        refresh.cancel()

        assertEquals(listOf("a", "a"), books.synced)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `after a successful sync of a server the server progress is merged`() = runTest {
        val books = RecordingBooks()
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.requestSync("a")

        assertEquals(listOf("a"), books.synced)
        assertEquals(listOf("a"), books.mergedServers)
    }

    @Test
    fun `a failed sync merges nothing`() = runTest {
        val books = RecordingBooks(outcome = { Result.failure(IllegalStateException("down")) })
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.requestSync("a")

        assertTrue(books.mergedServers.isEmpty())
    }

    @Test
    fun `a server that needs sign-in merges nothing`() = runTest {
        val books = RecordingBooks()
        val c = coordinator(books, FakeServerList(listOf(server("a", token = ""))))

        c.requestSync("a")

        assertTrue(books.mergedServers.isEmpty())
    }

    @Test
    fun `a Plex server merges nothing`() = runTest {
        val books = RecordingBooks()
        val plex = server("p").copy(source = ServerSource.PLEX)
        val c = coordinator(books, FakeServerList(listOf(plex)))

        c.requestSync("p")

        assertEquals(listOf("p"), books.synced)
        assertTrue(books.mergedServers.isEmpty())
    }

    @Test
    fun `a merge that throws neither fails the sync nor shows an error`() = runTest {
        val books = RecordingBooks()
        books.onMergeAll = { error("merge broke") }
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.requestSync("a")

        assertEquals(listOf("a"), books.mergedServers)
        assertEquals(null, c.state.value.syncError)
        assertFalse(c.state.value.isSyncing)
    }

    @Test
    fun `cancellation inside the merge is not swallowed`() = runTest {
        val books = RecordingBooks()
        books.onMergeAll = { throw kotlinx.coroutines.CancellationException("stop") }
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        var finished = false
        val job = launch {
            c.syncNowAll()
            finished = true
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(finished)
    }

    @Test
    fun `after a successful sync of an Audiobookshelf server its bookmarks are pulled`() = runTest {
        val books = RecordingBooks()
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.requestSync("a")

        assertEquals(listOf("a"), books.pulledBookmarks)
    }

    @Test
    fun `a failed sync pulls no bookmarks`() = runTest {
        val books = RecordingBooks(outcome = { Result.failure(IllegalStateException("down")) })
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        c.requestSync("a")

        assertTrue(books.pulledBookmarks.isEmpty())
    }

    @Test
    fun `a Plex server pulls no bookmarks`() = runTest {
        val books = RecordingBooks()
        val plex = ServerConfig("p", ServerSource.PLEX, "Plex", "http://p.test", "t")
        val c = coordinator(books, FakeServerList(listOf(plex)))

        c.requestSync("p")

        assertTrue(books.pulledBookmarks.isEmpty())
    }

    @Test
    fun `a bookmark pull that throws or fails neither fails the sync nor shows an error`() = runTest {
        val books = RecordingBooks()
        books.onPullBookmarks = { error("pull broke") }
        val c = coordinator(books, FakeServerList(listOf(server("a"), server("b"))))

        c.syncNowAll()

        assertEquals(listOf("a", "b"), books.pulledBookmarks)
        assertEquals(null, c.state.value.syncError)
        books.onPullBookmarks = { Result.failure(IllegalStateException("down")) }
        c.syncNowAll()
        assertEquals(null, c.state.value.syncError)
    }

    @Test
    fun `cancellation inside the bookmark pull is not swallowed`() = runTest {
        val books = RecordingBooks()
        books.onPullBookmarks = { throw kotlinx.coroutines.CancellationException("stop") }
        val c = coordinator(books, FakeServerList(listOf(server("a"))))

        var finished = false
        val job = launch {
            c.syncNowAll()
            finished = true
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(finished)
    }
}
