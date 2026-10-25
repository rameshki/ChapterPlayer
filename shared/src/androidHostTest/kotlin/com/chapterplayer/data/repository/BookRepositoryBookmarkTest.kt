package com.chapterplayer.data.repository

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.BookmarkDao
import com.chapterplayer.data.local.dao.HistoryDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.BookmarkEntity
import com.chapterplayer.data.local.entity.HistoryEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import io.ktor.http.HttpStatusCode
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import com.chapterplayer.domain.playback.HistoryEntry
import com.chapterplayer.testing.FakeServerList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bookmarks and position history through the repository, with in-memory DAOs that match the SQL. */
class BookRepositoryBookmarkTest {

    private class FakeBookDao(initial: List<BookEntity>) : BookDao {
        val books = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<BookEntity>> = books
        override fun observeBySource(source: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.source == source } }
        override fun observeByServer(serverId: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.serverId == serverId } }
        override suspend fun getById(id: String): BookEntity? = books.value.firstOrNull { it.id == id }
        override suspend fun getByServer(serverId: String): List<BookEntity> = books.value.filter { it.serverId == serverId }
        override suspend fun insertAll(books: List<BookEntity>) {}
        override suspend fun insert(book: BookEntity) {}
        override suspend fun deleteByServer(serverId: String) {}
        override suspend fun deleteByLibrary(serverId: String, libraryId: String) {}
        override suspend fun deleteAll() {}
        override suspend fun getLastCacheTime(serverId: String): Long? = null
        override suspend fun updateChapters(id: String, json: String) {}
        override suspend fun updateDuration(id: String, durationMs: Long) {}
    }

    private class BookmarkSource(override val type: ServerSource = ServerSource.ABS) : LibrarySource {
        var onServer: List<Bookmark> = emptyList()
        var fetchFailure: Throwable? = null
        /** Runs after the server list is read and before fetchBookmarks returns it. */
        var afterFetch: suspend () -> Unit = {}
        /** Called before each write, with the call text; throw to fail it, or change the DAO to simulate a change meanwhile. */
        var beforeWrite: suspend (String) -> Unit = {}
        val calls = mutableListOf<String>()
        override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> = emptyList()
        override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = emptyList()
        override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()
        override suspend fun fetchBookmarks(server: ServerConfig): List<Bookmark> {
            calls += "fetch"
            fetchFailure?.let { throw it }
            val snapshot = onServer
            afterFetch()
            return snapshot
        }
        override suspend fun createBookmark(server: ServerConfig, bookId: String, timeSec: Long, title: String) {
            val call = "create $bookId $timeSec $title"
            calls += call
            beforeWrite(call)
        }
        override suspend fun renameBookmark(server: ServerConfig, bookId: String, timeSec: Long, title: String) {
            val call = "rename $bookId $timeSec $title"
            calls += call
            beforeWrite(call)
        }
        override suspend fun deleteBookmark(server: ServerConfig, bookId: String, timeSec: Long) {
            val call = "delete $bookId $timeSec"
            calls += call
            beforeWrite(call)
        }
    }

    private fun entity(id: String, serverId: String = "srv1") =
        BookEntity(id, "T$id", "A", "N", 1L, "[]", "u", null, "ABS", serverId, 0L)

    private fun server(id: String = "srv1", source: ServerSource = ServerSource.ABS) =
        ServerConfig(id, source, "S", "http://s.test", "tok-secret")

    private fun bm(book: String, sec: Long, title: String = "t$sec", at: Long = 100 + sec, state: BookmarkState = BookmarkState.SYNCED) =
        Bookmark(book, sec, title, at, state)

    private fun row(b: Bookmark, serverId: String = "srv1") = BookmarkEntity.fromDomain(b, serverId)

    private class Fixture(
        val repo: BookRepositoryImpl,
        val source: BookmarkSource,
        val bookmarks: InMemoryBookmarkDao,
        val history: InMemoryHistoryDao,
    ) {
        fun domain(): List<Bookmark> =
            bookmarks.rows.value.map { it.toDomain() }.sortedWith(compareBy({ it.bookId }, { it.timeSec }))
    }

    private fun fixture(
        source: BookmarkSource = BookmarkSource(),
        servers: List<ServerConfig> = listOf(server()),
        books: List<BookEntity> = listOf(entity("abs_a"), entity("abs_b")),
        local: List<Bookmark> = emptyList(),
        historyRows: List<HistoryEntity> = emptyList(),
        now: Long = 5_000,
    ): Fixture {
        val bookmarks = InMemoryBookmarkDao(local.map { row(it) })
        val history = InMemoryHistoryDao(historyRows)
        val repo = BookRepositoryImpl(
            FakeBookDao(books), InMemoryProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(servers), listOf(source), pendingDao = InMemoryPendingProgressDao(),
            bookmarkDao = bookmarks, historyDao = history, clock = { now }
        )
        return Fixture(repo, source, bookmarks, history)
    }

    // ---- add ----

    @Test
    fun `a new bookmark is a pending create at the whole second with the cached server`() = runTest {
        val f = fixture(books = listOf(entity("abs_a", "srv1")))

        val saved = f.repo.addBookmark("abs_a", 62_999, "  Nice part ")

        assertEquals(Bookmark("abs_a", 62, "Nice part", 5_000, BookmarkState.PENDING_CREATE), saved)
        assertEquals(listOf(row(saved)), f.bookmarks.rows.value)
        assertEquals("srv1", f.bookmarks.rows.value.single().serverId)
    }

    @Test
    fun `no title or a blank one gives the default for the position and a negative position is zero`() = runTest {
        val f = fixture()

        assertEquals("Bookmark at 1:02:03", f.repo.addBookmark("abs_a", 3_723_400, null).title)
        assertEquals("Bookmark at 0:05", f.repo.addBookmark("abs_a", 5_000, "   ").title)
        val negative = f.repo.addBookmark("abs_a", -5_000, "x")
        assertEquals(0L, negative.timeSec)
    }

    @Test
    fun `adding for a book that is not in the library fails and writes nothing`() = runTest {
        val f = fixture()

        assertFailsWith<IllegalStateException> { f.repo.addBookmark("abs_gone", 1_000, "x") }
        assertTrue(f.bookmarks.rows.value.isEmpty())
    }

    @Test
    fun `adding in the same second replaces the title and keeps the creation time`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 10, "old", at = 77)))

        val saved = f.repo.addBookmark("abs_a", 10_900, "new")

        assertEquals(Bookmark("abs_a", 10, "new", 77, BookmarkState.PENDING_RENAME), saved)
        assertEquals(listOf(saved), f.domain())
    }

    @Test
    fun `adding in the same second as a pending create stays a create`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 10, "old", at = 77, state = BookmarkState.PENDING_CREATE)))

        f.repo.addBookmark("abs_a", 10_000, "new")

        assertEquals(listOf(bm("abs_a", 10, "new", at = 77, state = BookmarkState.PENDING_CREATE)), f.domain())
    }

    @Test
    fun `adding in the second of a bookmark waiting to be deleted brings it back as a rename`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 10, "old", at = 77, state = BookmarkState.PENDING_DELETE)))

        f.repo.addBookmark("abs_a", 10_000, "back")

        assertEquals(listOf(bm("abs_a", 10, "back", at = 77, state = BookmarkState.PENDING_RENAME)), f.domain())
    }

    // ---- rename ----

    @Test
    fun `renaming a synced bookmark makes it a pending rename`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 10, "old")))

        f.repo.renameBookmark("abs_a", 10, " better ")

        assertEquals(listOf(bm("abs_a", 10, "better", state = BookmarkState.PENDING_RENAME)), f.domain())
    }

    @Test
    fun `renaming a pending create keeps it a create and a pending rename stays a rename`() = runTest {
        val f = fixture(local = listOf(
            bm("abs_a", 10, "old", state = BookmarkState.PENDING_CREATE),
            bm("abs_a", 20, "old", state = BookmarkState.PENDING_RENAME),
        ))

        f.repo.renameBookmark("abs_a", 10, "n1")
        f.repo.renameBookmark("abs_a", 20, "n2")

        assertEquals(
            listOf(
                bm("abs_a", 10, "n1", state = BookmarkState.PENDING_CREATE),
                bm("abs_a", 20, "n2", state = BookmarkState.PENDING_RENAME),
            ),
            f.domain()
        )
    }

    @Test
    fun `a blank new name falls back to the default for that time`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 3_723, "old")))

        f.repo.renameBookmark("abs_a", 3_723, "  ")

        assertEquals("Bookmark at 1:02:03", f.domain().single().title)
    }

    @Test
    fun `renaming a missing bookmark or one waiting to be deleted changes nothing`() = runTest {
        val waiting = bm("abs_a", 10, "old", state = BookmarkState.PENDING_DELETE)
        val f = fixture(local = listOf(waiting))

        f.repo.renameBookmark("abs_a", 10, "new")
        f.repo.renameBookmark("abs_a", 99, "new")

        assertEquals(listOf(waiting), f.domain())
    }

    // ---- delete ----

    @Test
    fun `deleting a pending create removes it so it never reaches the server`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 10, state = BookmarkState.PENDING_CREATE)))

        f.repo.deleteBookmark("abs_a", 10)

        assertTrue(f.bookmarks.rows.value.isEmpty())
        assertTrue(f.repo.pushPendingBookmarks("srv1").isSuccess)
        assertEquals(emptyList(), f.source.calls)
    }

    @Test
    fun `deleting a synced or renamed bookmark makes it a pending delete`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 10), bm("abs_a", 20, state = BookmarkState.PENDING_RENAME)))

        f.repo.deleteBookmark("abs_a", 10)
        f.repo.deleteBookmark("abs_a", 20)
        f.repo.deleteBookmark("abs_a", 99)

        assertEquals(listOf(BookmarkState.PENDING_DELETE, BookmarkState.PENDING_DELETE), f.domain().map { it.state })
    }

    @Test
    fun `observing hides rows waiting to be deleted and orders by time`() = runTest {
        val f = fixture(local = listOf(
            bm("abs_a", 30), bm("abs_a", 10), bm("abs_a", 20, state = BookmarkState.PENDING_DELETE), bm("abs_b", 5)
        ))

        assertEquals(listOf(10L, 30L), f.repo.observeBookmarks("abs_a").first().map { it.timeSec })
        f.repo.addBookmark("abs_a", 15_000, "mid")
        assertEquals(listOf(10L, 15L, 30L), f.repo.observeBookmarks("abs_a").first().map { it.timeSec })
    }

    @Test
    fun `adding renaming and deleting each read and write inside one transaction`() = runTest {
        val events = mutableListOf<String>()
        val inner = InMemoryBookmarkDao(listOf(row(bm("abs_a", 10))))
        val dao = object : BookmarkDao by inner {
            override suspend fun get(bookId: String, timeSec: Long): BookmarkEntity? { events += "get"; return inner.get(bookId, timeSec) }
            override suspend fun upsert(row: BookmarkEntity) { events += "upsert"; inner.upsert(row) }
            override suspend fun delete(bookId: String, timeSec: Long) { events += "delete"; inner.delete(bookId, timeSec) }
        }
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(entity("abs_a"))), InMemoryProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(), listOf(BookmarkSource()), pendingDao = InMemoryPendingProgressDao(),
            bookmarkDao = dao, historyDao = InMemoryHistoryDao(),
            inTransaction = { block -> events += "begin"; block(); events += "end" }
        )

        repo.addBookmark("abs_a", 10_000, "x")
        repo.renameBookmark("abs_a", 10, "y")
        repo.deleteBookmark("abs_a", 10)

        assertEquals(
            listOf(
                "begin", "get", "upsert", "end",
                "begin", "get", "upsert", "end",
                "begin", "get", "upsert", "end",
            ),
            events
        )
    }

    // ---- pull ----

    @Test
    fun `pulling stores the server bookmarks of cached books as synced rows`() = runTest {
        val f = fixture(source = BookmarkSource().apply { onServer = listOf(bm("abs_a", 10), bm("abs_a", 20), bm("abs_b", 5)) })

        assertTrue(f.repo.pullBookmarks("srv1").isSuccess)

        assertEquals(listOf(bm("abs_a", 10), bm("abs_a", 20), bm("abs_b", 5)), f.domain())
        assertEquals(setOf("srv1"), f.bookmarks.rows.value.map { it.serverId }.toSet())
    }

    @Test
    fun `pulling follows the merge rules for every state`() = runTest {
        val local = listOf(
            bm("abs_a", 1, "local", at = 1),                                           // synced, server has a newer title
            bm("abs_a", 2, "mine", at = 2, state = BookmarkState.PENDING_CREATE),      // server already has it
            bm("abs_a", 3, "renamed", at = 3, state = BookmarkState.PENDING_RENAME),   // local rename stays
            bm("abs_a", 4, "gone", at = 4, state = BookmarkState.PENDING_DELETE),      // local delete stays
            bm("abs_a", 5, "elsewhere", at = 5),                                       // deleted on the server: dropped
            bm("abs_a", 6, "new", at = 6, state = BookmarkState.PENDING_CREATE),       // not on the server: stays
            bm("abs_a", 7, "lost", at = 7, state = BookmarkState.PENDING_RENAME),      // server deleted it: dropped
            bm("abs_a", 8, "done", at = 8, state = BookmarkState.PENDING_DELETE),      // already gone: dropped
        )
        val server = listOf(
            bm("abs_a", 1, "server", at = 11), bm("abs_a", 2, "server2", at = 12), bm("abs_a", 3, "server3", at = 13),
            bm("abs_a", 4, "server4", at = 14), bm("abs_a", 9, "other device", at = 19),
        )
        val f = fixture(source = BookmarkSource().apply { onServer = server }, local = local)

        f.repo.pullBookmarks("srv1")

        assertEquals(
            listOf(
                bm("abs_a", 1, "server", at = 11),
                bm("abs_a", 2, "server2", at = 12),
                bm("abs_a", 3, "renamed", at = 3, state = BookmarkState.PENDING_RENAME),
                bm("abs_a", 4, "gone", at = 4, state = BookmarkState.PENDING_DELETE),
                bm("abs_a", 6, "new", at = 6, state = BookmarkState.PENDING_CREATE),
                bm("abs_a", 9, "other device", at = 19),
            ),
            f.domain()
        )
        // Nothing is pushed by a pull.
        assertEquals(listOf("fetch"), f.source.calls)
    }

    @Test
    fun `a book with local rows and nothing on the server is merged too`() = runTest {
        val f = fixture(local = listOf(bm("abs_b", 1, "was synced"), bm("abs_b", 2, "pending", state = BookmarkState.PENDING_CREATE)))

        f.repo.pullBookmarks("srv1")

        assertEquals(listOf(bm("abs_b", 2, "pending", state = BookmarkState.PENDING_CREATE)), f.domain())
    }

    @Test
    fun `books that are not in the library get no server rows and keep only pending local rows`() = runTest {
        val f = fixture(
            source = BookmarkSource().apply { onServer = listOf(bm("abs_gone", 1), bm("abs_a", 3)) },
            local = listOf(
                bm("abs_gone", 5, "synced leftover"),
                bm("abs_gone", 6, "mine", state = BookmarkState.PENDING_CREATE),
                bm("abs_gone", 7, "renamed", state = BookmarkState.PENDING_RENAME),
            )
        )

        f.repo.pullBookmarks("srv1")

        assertEquals(
            listOf(
                bm("abs_a", 3),
                bm("abs_gone", 6, "mine", state = BookmarkState.PENDING_CREATE),
                bm("abs_gone", 7, "renamed", state = BookmarkState.PENDING_RENAME),
            ),
            f.domain()
        )
    }

    @Test
    fun `pulling leaves the rows of other servers alone`() = runTest {
        val other = BookmarkEntity.fromDomain(bm("abs_x", 1), "srv2")
        val f = fixture(local = listOf(bm("abs_a", 1)))
        f.bookmarks.upsert(other)

        f.repo.pullBookmarks("srv1")

        assertEquals(listOf(other), f.bookmarks.rows.value)
    }

    @Test
    fun `a pull replaces only the rows of its own server for a book`() = runTest {
        val other = BookmarkEntity.fromDomain(bm("abs_a", 99), "srv2")
        val f = fixture(local = listOf(bm("abs_a", 1)))
        f.bookmarks.upsert(other)

        f.repo.pullBookmarks("srv1")

        assertTrue(other in f.bookmarks.rows.value)
    }

    @Test
    fun `a push started while a pull is between its fetch and its write waits, so the pull cannot undo it`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 10, "mine", at = 50, state = BookmarkState.PENDING_CREATE)))
        // The server snapshot the pull took has no such bookmark.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.source.afterFetch = { gate.await() }
        val pull = launch { f.repo.pullBookmarks("srv1") }
        runCurrent()
        val push = launch { f.repo.pushPendingBookmarks("srv1") }
        runCurrent()
        assertEquals(listOf("fetch"), f.source.calls)
        gate.complete(Unit)
        pull.join()
        push.join()
        assertEquals(listOf("fetch", "create abs_a 10 mine"), f.source.calls)
        assertEquals(listOf(bm("abs_a", 10, "mine", at = 50, state = BookmarkState.SYNCED)), f.domain())
    }

    @Test
    fun `a failed fetch is returned and the rows stay as they were`() = runTest {
        val local = listOf(bm("abs_a", 1), bm("abs_a", 2, state = BookmarkState.PENDING_CREATE))
        val f = fixture(source = BookmarkSource().apply { fetchFailure = IOException("boom") }, local = local)

        val result = f.repo.pullBookmarks("srv1")

        assertIs<IOException>(result.exceptionOrNull())
        assertEquals(local, f.domain())
    }

    @Test
    fun `a refused sign-in is returned as such`() = runTest {
        val f = fixture(source = BookmarkSource().apply { fetchFailure = SignInRequiredException("S") })

        assertIs<SignInRequiredException>(f.repo.pullBookmarks("srv1").exceptionOrNull())
    }

    @Test
    fun `an unknown server fails safely and a Plex server is left alone`() = runTest {
        val unknown = fixture(servers = emptyList())
        val failure = unknown.repo.pullBookmarks("srv1").exceptionOrNull()
        assertEquals("The book's server is no longer configured", failure?.message)
        assertFalse(failure?.message.orEmpty().contains("tok-secret"))

        val plexSource = BookmarkSource(ServerSource.PLEX)
        val plexLocal = listOf(bm("plex_a", 5, state = BookmarkState.PENDING_CREATE))
        val plex = fixture(
            source = plexSource, servers = listOf(server(source = ServerSource.PLEX)), books = listOf(entity("plex_a")), local = plexLocal
        )
        assertTrue(plex.repo.pullBookmarks("srv1").isSuccess)
        assertTrue(plex.repo.pushPendingBookmarks("srv1").isSuccess)
        assertEquals(emptyList(), plexSource.calls)
        assertEquals(plexLocal, plex.domain())
    }

    @Test
    fun `each book is replaced inside one transaction`() = runTest {
        val events = mutableListOf<String>()
        val inner = InMemoryBookmarkDao(listOf(row(bm("abs_a", 1))))
        val dao = object : BookmarkDao by inner {
            override suspend fun getByServer(serverId: String): List<BookmarkEntity> { events += "read"; return inner.getByServer(serverId) }
            override suspend fun deleteForBook(bookId: String, serverId: String) { events += "deleteBook $bookId"; inner.deleteForBook(bookId, serverId) }
            override suspend fun insertAll(rows: List<BookmarkEntity>) { events += "insert ${rows.size}"; inner.insertAll(rows) }
        }
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(entity("abs_a"), entity("abs_b"))), InMemoryProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(listOf(server())), listOf(BookmarkSource().apply { onServer = listOf(bm("abs_a", 2), bm("abs_b", 3)) }),
            pendingDao = InMemoryPendingProgressDao(), bookmarkDao = dao, historyDao = InMemoryHistoryDao(),
            inTransaction = { block -> events += "begin"; block(); events += "end" }
        )

        repo.pullBookmarks("srv1")

        // The first read is outside (to learn the book ids); each book then reads, deletes and inserts between begin and end.
        assertEquals(
            listOf(
                "read",
                "begin", "read", "deleteBook abs_a", "insert 1", "end",
                "begin", "read", "deleteBook abs_b", "insert 1", "end",
            ),
            events
        )
    }

    // ---- push ----

    @Test
    fun `each pending state goes to the server and is settled`() = runTest {
        val f = fixture(local = listOf(
            bm("abs_a", 1, "c", at = 10, state = BookmarkState.PENDING_CREATE),
            bm("abs_a", 2, "r", at = 20, state = BookmarkState.PENDING_RENAME),
            bm("abs_a", 3, "d", at = 30, state = BookmarkState.PENDING_DELETE),
            bm("abs_a", 4, "s", at = 40),
        ))

        assertTrue(f.repo.pushPendingBookmarks("srv1").isSuccess)

        assertEquals(listOf("create abs_a 1 c", "rename abs_a 2 r", "delete abs_a 3"), f.source.calls)
        assertEquals(
            listOf(bm("abs_a", 1, "c", at = 10), bm("abs_a", 2, "r", at = 20), bm("abs_a", 4, "s", at = 40)),
            f.domain()
        )
    }

    @Test
    fun `pending rows go oldest first`() = runTest {
        val f = fixture(local = listOf(
            bm("abs_a", 1, "x", at = 300, state = BookmarkState.PENDING_CREATE),
            bm("abs_b", 2, "y", at = 100, state = BookmarkState.PENDING_CREATE),
            bm("abs_a", 3, "z", at = 200, state = BookmarkState.PENDING_CREATE),
        ))

        f.repo.pushPendingBookmarks("srv1")

        assertEquals(listOf("create abs_b 2 y", "create abs_a 3 z", "create abs_a 1 x"), f.source.calls)
    }

    @Test
    fun `a create or rename the server answers with not found is dropped, any other failure stays pending`() = runTest {
        val source = BookmarkSource().apply {
            beforeWrite = { call ->
                when {
                    call.startsWith("create abs_a") -> throw HttpStatusException(HttpStatusCode.NotFound)
                    call.startsWith("rename abs_a") -> throw HttpStatusException(HttpStatusCode.NotFound)
                    call.startsWith("create abs_b") -> throw HttpStatusException(HttpStatusCode.InternalServerError)
                }
            }
        }
        val f = fixture(source = source, local = listOf(
            bm("abs_a", 1, "c", at = 10, state = BookmarkState.PENDING_CREATE),
            bm("abs_a", 2, "r", at = 20, state = BookmarkState.PENDING_RENAME),
            bm("abs_b", 3, "k", at = 30, state = BookmarkState.PENDING_CREATE),
        ))

        val result = f.repo.pushPendingBookmarks("srv1")

        assertTrue(result.isFailure)
        assertEquals(listOf(bm("abs_b", 3, "k", at = 30, state = BookmarkState.PENDING_CREATE)), f.domain())
    }

    @Test
    fun `a failing row stays pending and the others still go`() = runTest {
        val source = BookmarkSource().apply {
            beforeWrite = { call -> if (call.startsWith("rename")) throw IOException("down") }
        }
        val f = fixture(source = source, local = listOf(
            bm("abs_a", 1, "c", at = 10, state = BookmarkState.PENDING_CREATE),
            bm("abs_a", 2, "r", at = 20, state = BookmarkState.PENDING_RENAME),
            bm("abs_a", 3, "d", at = 30, state = BookmarkState.PENDING_DELETE),
        ))

        val result = f.repo.pushPendingBookmarks("srv1")

        assertIs<IOException>(result.exceptionOrNull())
        assertEquals(3, f.source.calls.size)
        assertEquals(
            listOf(bm("abs_a", 1, "c", at = 10), bm("abs_a", 2, "r", at = 20, state = BookmarkState.PENDING_RENAME)),
            f.domain()
        )
    }

    @Test
    fun `a refused sign-in stops the whole push`() = runTest {
        val source = BookmarkSource().apply { beforeWrite = { throw SignInRequiredException("S") } }
        val f = fixture(source = source, local = listOf(
            bm("abs_a", 1, at = 10, state = BookmarkState.PENDING_CREATE),
            bm("abs_a", 2, at = 20, state = BookmarkState.PENDING_CREATE),
        ))

        val result = f.repo.pushPendingBookmarks("srv1")

        assertIs<SignInRequiredException>(result.exceptionOrNull())
        assertEquals(1, f.source.calls.size)
        assertEquals(2, f.domain().count { it.state == BookmarkState.PENDING_CREATE })
    }

    @Test
    fun `nothing pending is a quiet success`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 1)))

        assertTrue(f.repo.pushPendingBookmarks("srv1").isSuccess)
        assertEquals(emptyList(), f.source.calls)
    }

    @Test
    fun `only the rows of the given server are pushed`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 1, state = BookmarkState.PENDING_CREATE)))
        f.bookmarks.upsert(BookmarkEntity.fromDomain(bm("abs_x", 9, state = BookmarkState.PENDING_CREATE), "srv2"))

        f.repo.pushPendingBookmarks("srv1")

        assertEquals(listOf("create abs_a 1 t1"), f.source.calls)
    }

    @Test
    fun `a rename made while the create was on its way is pushed next time`() = runTest {
        val source = BookmarkSource()
        val f = fixture(source = source, local = listOf(bm("abs_a", 1, "first", state = BookmarkState.PENDING_CREATE)))
        source.beforeWrite = { f.repo.renameBookmark("abs_a", 1, "second") }

        f.repo.pushPendingBookmarks("srv1")

        // The server has "first": the row must go on as a rename to "second", not be marked done.
        assertEquals(listOf(bm("abs_a", 1, "second", state = BookmarkState.PENDING_RENAME)), f.domain())
    }

    @Test
    fun `a delete made while the create was on its way also removes it on the server`() = runTest {
        val source = BookmarkSource()
        val f = fixture(source = source, local = listOf(bm("abs_a", 1, "first", state = BookmarkState.PENDING_CREATE)))
        source.beforeWrite = { call -> if (call.startsWith("create")) f.repo.deleteBookmark("abs_a", 1) }

        f.repo.pushPendingBookmarks("srv1")

        assertEquals(listOf("create abs_a 1 first", "delete abs_a 1"), source.calls)
        assertTrue(f.bookmarks.rows.value.isEmpty())
    }

    @Test
    fun `a rename changed while the old one was on its way stays pending with the new title`() = runTest {
        val source = BookmarkSource()
        val f = fixture(source = source, local = listOf(bm("abs_a", 1, "first", state = BookmarkState.PENDING_RENAME)))
        source.beforeWrite = { f.repo.renameBookmark("abs_a", 1, "second") }

        f.repo.pushPendingBookmarks("srv1")

        assertEquals(listOf(bm("abs_a", 1, "second", state = BookmarkState.PENDING_RENAME)), f.domain())
    }

    @Test
    fun `a bookmark brought back while its delete was on its way survives`() = runTest {
        val source = BookmarkSource()
        val f = fixture(source = source, local = listOf(bm("abs_a", 1, "first", state = BookmarkState.PENDING_DELETE)))
        source.beforeWrite = { f.repo.addBookmark("abs_a", 1_000, "again") }

        f.repo.pushPendingBookmarks("srv1")

        assertEquals(listOf(bm("abs_a", 1, "again", state = BookmarkState.PENDING_CREATE)), f.domain())
    }

    @Test
    fun `a pending delete is removed after the server confirms`() = runTest {
        val f = fixture(local = listOf(bm("abs_a", 1, state = BookmarkState.PENDING_DELETE)))

        f.repo.pushPendingBookmarks("srv1")

        assertNull(f.bookmarks.get("abs_a", 1))
    }

    // ---- history ----

    private fun entry(book: String, pos: Long, at: Long) = HistoryEntry(book, pos, at)

    private fun HistoryEntity.entry() = toDomain()

    @Test
    fun `history is newest first and is per book`() = runTest {
        val f = fixture()

        f.repo.recordHistory(entry("abs_a", 100_000, 1_000))
        f.repo.recordHistory(entry("abs_b", 50_000, 1_500))
        f.repo.recordHistory(entry("abs_a", 300_000, 2_000))

        assertEquals(
            listOf(entry("abs_a", 300_000, 2_000), entry("abs_a", 100_000, 1_000)),
            f.repo.observeHistory("abs_a").first()
        )
        assertEquals(listOf(entry("abs_b", 50_000, 1_500)), f.repo.observeHistory("abs_b").first())
    }

    @Test
    fun `an entry near an existing one replaces it`() = runTest {
        val f = fixture()
        f.repo.recordHistory(entry("abs_a", 100_000, 1_000))
        f.repo.recordHistory(entry("abs_a", 500_000, 2_000))

        f.repo.recordHistory(entry("abs_a", 103_000, 3_000))

        assertEquals(
            listOf(entry("abs_a", 103_000, 3_000), entry("abs_a", 500_000, 2_000)),
            f.repo.observeHistory("abs_a").first()
        )
        assertEquals(2, f.history.rows.value.size)
    }

    @Test
    fun `history keeps the last ten and removes the older rows`() = runTest {
        val f = fixture()
        for (i in 1..12) f.repo.recordHistory(entry("abs_a", i * 100_000L, i * 1_000L))
        f.repo.recordHistory(entry("abs_b", 1_000, 99))

        val list = f.repo.observeHistory("abs_a").first()

        assertEquals((12 downTo 3).map { it * 100_000L }, list.map { it.positionMs })
        assertEquals(11, f.history.rows.value.size)
    }

    @Test
    fun `recording reads and writes history inside one transaction`() = runTest {
        val events = mutableListOf<String>()
        val inner = InMemoryHistoryDao(listOf(HistoryEntity(1, "abs_a", 100_000, 1_000), HistoryEntity(2, "abs_a", 500_000, 2_000)))
        val dao = object : HistoryDao by inner {
            override suspend fun getForBook(bookId: String): List<HistoryEntity> { events += "get"; return inner.getForBook(bookId) }
            override suspend fun deleteByIds(ids: List<Long>) { events += "delete $ids"; inner.deleteByIds(ids) }
            override suspend fun insert(row: HistoryEntity): Long { events += "insert"; return inner.insert(row) }
        }
        val repo = BookRepositoryImpl(
            FakeBookDao(emptyList()), InMemoryProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(), listOf(BookmarkSource()), pendingDao = InMemoryPendingProgressDao(),
            bookmarkDao = InMemoryBookmarkDao(), historyDao = dao,
            inTransaction = { block -> events += "begin"; block(); events += "end" }
        )

        repo.recordHistory(entry("abs_a", 101_000, 3_000))

        assertEquals(listOf("begin", "get", "delete [1]", "insert", "end"), events)
    }
}
