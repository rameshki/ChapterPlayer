package com.chapterplayer.data.repository

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.PendingProgressDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.PendingProgressEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeServerList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Session sync, the pending queue and the server progress merge through the repository. */
class BookRepositoryProgressTest {

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

    private class ProgressSource(override val type: ServerSource = ServerSource.ABS) : LibrarySource {
        var failure: Throwable? = null
        var fetched: ServerProgress? = null
        var all: Map<String, ServerProgress> = emptyMap()
        val synced = mutableListOf<List<Any>>()
        val pushed = mutableListOf<List<Any>>()
        val fetchedFor = mutableListOf<String>()
        override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> = emptyList()
        override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = emptyList()
        override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()
        override suspend fun syncPlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long, durationMs: Long) {
            synced += listOf(server.id, sessionId, positionMs, listenedMs, durationMs)
            failure?.let { throw it }
        }
        override suspend fun fetchProgress(server: ServerConfig, bookId: String): ServerProgress? {
            fetchedFor += bookId
            failure?.let { throw it }
            return fetched
        }
        override suspend fun pushProgress(server: ServerConfig, bookId: String, positionMs: Long, durationMs: Long, isFinished: Boolean) {
            pushed += listOf(server.id, bookId, positionMs, durationMs, isFinished)
            failure?.let { throw it }
        }
        override suspend fun fetchAllProgress(server: ServerConfig): Map<String, ServerProgress> {
            failure?.let { throw it }
            return all
        }
    }

    private val session = PlaybackSession("sess1", "srv1", "abs_a", 9_000, 0, emptyList(), emptyList())

    private fun entity(id: String, serverId: String = "srv1") =
        BookEntity(id, "T$id", "A", "N", 1L, "[]", "u", null, "ABS", serverId, 0L)

    private fun server(id: String = "srv1") = ServerConfig(id, ServerSource.ABS, "S", "http://s.test", "tok-secret")

    private fun progress(id: String, pos: Long = 1_000, at: Long = 100, finished: Boolean = false) =
        ProgressEntity(id, pos, 9_000, at, finished)

    private fun pending(id: String = "abs_a", pos: Long = 1_000, listened: Long = 500, at: Long = 100, attempts: Int = 0, next: Long = 0) =
        PendingProgress(id, "srv1", pos, 9_000, listened, at, attempts, next)

    private class Fixture(
        val repo: BookRepositoryImpl,
        val source: ProgressSource,
        val progressDao: InMemoryProgressDao,
        val pendingDao: InMemoryPendingProgressDao,
    )

    private fun fixture(
        source: ProgressSource = ProgressSource(),
        servers: List<ServerConfig> = listOf(server()),
        books: List<BookEntity> = listOf(entity("abs_a"), entity("abs_b")),
        localProgress: List<ProgressEntity> = emptyList(),
        queued: List<PendingProgressEntity> = emptyList(),
    ): Fixture {
        val progressDao = InMemoryProgressDao(localProgress)
        val pendingDao = InMemoryPendingProgressDao(queued)
        val repo = BookRepositoryImpl(
            FakeBookDao(books), progressDao, InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(servers), listOf(source), pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao()
        )
        return Fixture(repo, source, progressDao, pendingDao)
    }

    private fun row(p: PendingProgress, next: Long = 0) = PendingProgressEntity.fromDomain(p, next)

    // ---- session sync ----

    @Test
    fun `sync passes the session numbers and the book duration`() = runTest {
        val f = fixture()

        assertTrue(f.repo.syncPlayback(session, 5_000, 2_000).isSuccess)

        assertEquals(listOf<List<Any>>(listOf("srv1", "sess1", 5_000L, 2_000L, 9_000L)), f.source.synced)
    }

    @Test
    fun `sync never sends a negative position or listened time`() = runTest {
        val f = fixture()
        f.repo.syncPlayback(session, -5, -7)
        assertEquals(listOf<List<Any>>(listOf("srv1", "sess1", 0L, 0L, 9_000L)), f.source.synced)
    }

    @Test
    fun `sync fails safely for a gone server a missing source or a failing call`() = runTest {
        val gone = assertIs(fixture(servers = emptyList()).repo.syncPlayback(session, 1, 1).exceptionOrNull())
        assertTrue("tok-secret" !in gone.message.orEmpty() && "s.test" !in gone.message.orEmpty())

        assertTrue(fixture(source = ProgressSource(ServerSource.PLEX)).repo.syncPlayback(session, 1, 1).isFailure)

        val source = ProgressSource().also { it.failure = IOException("HTTP 404") }
        assertSame(source.failure, fixture(source = source).repo.syncPlayback(session, 1, 1).exceptionOrNull())
    }

    @Test
    fun `cancellation propagates from the server calls`() = runTest {
        val source = ProgressSource().also { it.failure = CancellationException("stop") }
        val f = fixture(source = source, queued = listOf(row(pending())))

        assertFailsWith<CancellationException> { f.repo.syncPlayback(session, 1, 1) }
        assertFailsWith<CancellationException> { f.repo.fetchServerProgress("abs_a") }
        assertFailsWith<CancellationException> { f.repo.pushProgress(pending()) }
        assertFailsWith<CancellationException> { f.repo.mergeAllServerProgress("srv1") }
    }

    @Test
    fun `a sign-in problem reaches the caller as the same instance`() = runTest {
        val signIn = SignInRequiredException("S")
        val source = ProgressSource().also { it.failure = signIn }
        val f = fixture(source = source)

        assertSame(signIn, f.repo.syncPlayback(session, 1, 1).exceptionOrNull())
        assertSame(signIn, f.repo.fetchServerProgress("abs_a").exceptionOrNull())
        assertSame(signIn, f.repo.pushProgress(pending()).exceptionOrNull())
    }

    // ---- reading and pushing one book ----

    @Test
    fun `fetching server progress asks the books server`() = runTest {
        val server = ServerProgress(1_000, 9_000, false, 5)
        val f = fixture(source = ProgressSource().also { it.fetched = server })

        assertEquals(server, f.repo.fetchServerProgress("abs_a").getOrThrow())
        assertEquals(listOf("abs_a"), f.source.fetchedFor)
    }

    @Test
    fun `no server progress is a success with null`() = runTest {
        assertNull(fixture().repo.fetchServerProgress("abs_a").getOrThrow())
    }

    @Test
    fun `fetching for an unknown book or a gone server fails safely and asks nobody`() = runTest {
        val f = fixture(servers = emptyList())
        val error = assertIs(f.repo.fetchServerProgress("abs_a").exceptionOrNull())
        assertTrue("tok-secret" !in error.message.orEmpty())
        assertTrue(fixture().repo.fetchServerProgress("abs_missing").isFailure)
        assertTrue(f.source.fetchedFor.isEmpty())
    }

    @Test
    fun `pushing sends the position and works out finished from the position`() = runTest {
        val f = fixture()

        assertTrue(f.repo.pushProgress(pending(pos = 4_000).copy(durationMs = 100_000)).isSuccess)
        assertTrue(f.repo.pushProgress(pending(pos = 96_000).copy(durationMs = 100_000)).isSuccess)

        assertEquals(listOf<Any>("srv1", "abs_a", 4_000L, 100_000L, false), f.source.pushed[0])
        assertEquals(listOf<Any>("srv1", "abs_a", 96_000L, 100_000L, true), f.source.pushed[1])
    }

    @Test
    fun `pushing uses the server named by the row and fails safely without it`() = runTest {
        val f = fixture(servers = emptyList())
        val error = assertIs(f.repo.pushProgress(pending()).exceptionOrNull())
        assertTrue("tok-secret" !in error.message.orEmpty() && "s.test" !in error.message.orEmpty())
        assertTrue(f.source.pushed.isEmpty())
    }

    @Test
    fun `a failing push is returned`() = runTest {
        val source = ProgressSource().also { it.failure = IOException("HTTP 500") }
        assertSame(source.failure, fixture(source = source).repo.pushProgress(pending()).exceptionOrNull())
    }

    // ---- the queue ----

    @Test
    fun `the first position for a book is stored as it is`() = runTest {
        val f = fixture()

        f.repo.queueProgress(pending(pos = 2_000, listened = 700, at = 50))

        assertEquals(listOf(pending(pos = 2_000, listened = 700, at = 50)), f.repo.pendingProgress())
        assertEquals(0L, f.pendingDao.get("abs_a")!!.nextAttemptAtMs)
    }

    @Test
    fun `a newer position replaces the old one and the listened time adds up`() = runTest {
        val f = fixture(queued = listOf(row(pending(pos = 1_000, listened = 500, at = 100, attempts = 3), next = 777)))

        f.repo.queueProgress(pending(pos = 3_000, listened = 250, at = 200))

        assertEquals(listOf(pending(pos = 3_000, listened = 750, at = 200, attempts = 0)), f.repo.pendingProgress())
        assertEquals(0L, f.pendingDao.get("abs_a")!!.nextAttemptAtMs)
    }

    @Test
    fun `an older position keeps the stored one and its retry state but adds the listened time`() = runTest {
        val f = fixture(queued = listOf(row(pending(pos = 3_000, listened = 500, at = 200, attempts = 2), next = 777)))

        f.repo.queueProgress(pending(pos = 1_000, listened = 250, at = 100))

        assertEquals(listOf(pending(pos = 3_000, listened = 750, at = 200, attempts = 2, next = 777)), f.repo.pendingProgress())
        assertEquals(777L, f.pendingDao.get("abs_a")!!.nextAttemptAtMs)
    }

    @Test
    fun `there is one row per book and the list is oldest first`() = runTest {
        val f = fixture()

        f.repo.queueProgress(pending("abs_b", at = 300))
        f.repo.queueProgress(pending("abs_a", at = 100))
        f.repo.queueProgress(pending("abs_b", at = 400))

        assertEquals(listOf("abs_a", "abs_b"), f.repo.pendingProgress().map { it.bookId })
        assertEquals(2, f.pendingDao.observeCount().first())
    }

    @Test
    fun `queueing reads merges and writes inside one transaction`() = runTest {
        val events = mutableListOf<String>()
        val inner = InMemoryPendingProgressDao()
        val pendingDao = object : PendingProgressDao by inner {
            override suspend fun get(bookId: String): PendingProgressEntity? { events += "get"; return inner.get(bookId) }
            override suspend fun upsert(row: PendingProgressEntity) { events += "upsert"; inner.upsert(row) }
        }
        val repo = BookRepositoryImpl(
            FakeBookDao(emptyList()), InMemoryProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(), listOf(ProgressSource()), pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(),
            inTransaction = { block -> events += "begin"; block(); events += "end" }
        )

        repo.queueProgress(pending())

        // The read and the write are BOTH between begin and end: nothing can slip in between them.
        assertEquals(listOf("begin", "get", "upsert", "end"), events)
        assertEquals(1, inner.rows.value.size)
    }

    @Test
    fun `saving server progress reads, decides and writes inside one transaction`() = runTest {
        val events = mutableListOf<String>()
        val innerProgress = InMemoryProgressDao()
        val progressDao = object : ProgressDao by innerProgress {
            override suspend fun get(bookId: String): ProgressEntity? { events += "getProgress"; return innerProgress.get(bookId) }
            override suspend fun upsert(progress: ProgressEntity) { events += "upsertProgress"; innerProgress.upsert(progress) }
        }
        val innerPending = InMemoryPendingProgressDao()
        val pendingDao = object : PendingProgressDao by innerPending {
            override suspend fun get(bookId: String): PendingProgressEntity? { events += "getPending"; return innerPending.get(bookId) }
        }
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(entity("abs_a"))), progressDao, InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(), listOf(ProgressSource()), pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(),
            inTransaction = { block -> events += "begin"; block(); events += "end" }
        )

        repo.saveServerProgress("abs_a", ServerProgress(5_000, 9_000, false, 200))

        assertEquals(listOf("begin", "getProgress", "getPending", "upsertProgress", "end"), events)
    }

    @Test
    fun `saving server progress for a book that is not in the library writes nothing`() = runTest {
        val f = fixture(books = listOf(entity("abs_a")))

        f.repo.saveServerProgress("abs_gone", ServerProgress(5_000, 9_000, false, 200))

        assertTrue(f.progressDao.rows.value.isEmpty())
    }

    @Test
    fun `deleting removes only that book`() = runTest {
        val f = fixture(queued = listOf(row(pending("abs_a")), row(pending("abs_b"))))

        f.repo.deletePending("abs_a")

        assertEquals(listOf("abs_b"), f.repo.pendingProgress().map { it.bookId })
        f.repo.deletePending("abs_missing")
        assertEquals(1, f.repo.pendingProgress().size)
    }

    @Test
    fun `marking an attempt stores the count and the next time without touching the position`() = runTest {
        val f = fixture(queued = listOf(row(pending(pos = 1_234, listened = 55))))

        f.repo.markPendingAttempt("abs_a", 3, 99_000)

        assertEquals(listOf(pending(pos = 1_234, listened = 55, attempts = 3, next = 99_000)), f.repo.pendingProgress())
        assertEquals(99_000L, f.pendingDao.get("abs_a")!!.nextAttemptAtMs)
        f.repo.markPendingAttempt("abs_missing", 1, 1)
        assertNull(f.pendingDao.get("abs_missing"))
    }

    // ---- saving server progress ----

    @Test
    fun `server progress is stored when nothing local exists`() = runTest {
        val f = fixture()

        f.repo.saveServerProgress("abs_a", ServerProgress(4_000, 9_000, false, 500))

        assertEquals(ProgressEntity("abs_a", 4_000, 9_000, 500, false), f.progressDao.get("abs_a"))
    }

    @Test
    fun `server progress newer than the local row replaces it and a finished flag is kept`() = runTest {
        val f = fixture(localProgress = listOf(progress("abs_a", pos = 1_000, at = 100)))

        f.repo.saveServerProgress("abs_a", ServerProgress(9_000, 9_000, true, 500))

        assertEquals(ProgressEntity("abs_a", 9_000, 9_000, 500, true), f.progressDao.get("abs_a"))
    }

    @Test
    fun `server progress never replaces a newer or equal local row`() = runTest {
        val f = fixture(localProgress = listOf(progress("abs_a", pos = 1_000, at = 500)))

        f.repo.saveServerProgress("abs_a", ServerProgress(8_000, 9_000, false, 500))
        f.repo.saveServerProgress("abs_a", ServerProgress(8_000, 9_000, false, 400))

        assertEquals(progress("abs_a", pos = 1_000, at = 500), f.progressDao.get("abs_a"))
    }

    @Test
    fun `server progress never replaces a book with a queued position`() = runTest {
        val f = fixture(queued = listOf(row(pending("abs_a", at = 100))))

        f.repo.saveServerProgress("abs_a", ServerProgress(8_000, 9_000, false, 900))

        assertNull(f.progressDao.get("abs_a"))
    }

    // ---- merging everything after a library sync ----

    @Test
    fun `the merge stores progress for cached books and skips the ones not in the cache`() = runTest {
        val source = ProgressSource().also {
            it.all = mapOf(
                "abs_a" to ServerProgress(4_000, 9_000, false, 500),
                "abs_unknown" to ServerProgress(1_000, 9_000, false, 500),
            )
        }
        val f = fixture(source = source)

        f.repo.mergeAllServerProgress("srv1")

        assertEquals(listOf("abs_a"), f.progressDao.rows.value.map { it.bookId })
    }

    @Test
    fun `the merge skips books of another server`() = runTest {
        val source = ProgressSource().also { it.all = mapOf("abs_other" to ServerProgress(1_000, 9_000, false, 500)) }
        val f = fixture(source = source, books = listOf(entity("abs_other", "srv2")))

        f.repo.mergeAllServerProgress("srv1")

        assertTrue(f.progressDao.rows.value.isEmpty())
    }

    @Test
    fun `the merge keeps newer local rows and queued books and updates older ones`() = runTest {
        val source = ProgressSource().also {
            it.all = mapOf(
                "abs_a" to ServerProgress(8_000, 9_000, false, 900),
                "abs_b" to ServerProgress(7_000, 9_000, false, 900),
                "abs_c" to ServerProgress(6_000, 9_000, false, 900),
            )
        }
        val f = fixture(
            source = source,
            books = listOf(entity("abs_a"), entity("abs_b"), entity("abs_c")),
            localProgress = listOf(progress("abs_a", pos = 1_000, at = 1_000), progress("abs_c", pos = 1_000, at = 100)),
            queued = listOf(row(pending("abs_b", at = 100))),
        )

        f.repo.mergeAllServerProgress("srv1")

        assertEquals(progress("abs_a", pos = 1_000, at = 1_000), f.progressDao.get("abs_a"))
        assertNull(f.progressDao.get("abs_b"))
        assertEquals(ProgressEntity("abs_c", 6_000, 9_000, 900, false), f.progressDao.get("abs_c"))
    }

    @Test
    fun `the merge never throws for a failing server a gone server or a missing source`() = runTest {
        fixture(source = ProgressSource().also { it.failure = IOException("down") }).repo.mergeAllServerProgress("srv1")
        fixture(source = ProgressSource().also { it.failure = SignInRequiredException("S") }).repo.mergeAllServerProgress("srv1")
        fixture(servers = emptyList()).repo.mergeAllServerProgress("srv1")
        fixture(source = ProgressSource(ServerSource.PLEX)).repo.mergeAllServerProgress("srv1")
    }

    private fun assertIs(error: Throwable?): Throwable {
        assertTrue(error != null, "expected a failure")
        return error!!
    }
}
