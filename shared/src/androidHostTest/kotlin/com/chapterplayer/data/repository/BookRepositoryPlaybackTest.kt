package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.testing.FakeServerList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Starting and closing a playback session through the repository. */
class BookRepositoryPlaybackTest {

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

    private class NoProgressDao : ProgressDao {
        override fun observe(bookId: String): Flow<ProgressEntity?> = flowOf(null)
        override suspend fun get(bookId: String): ProgressEntity? = null
        override fun observeInProgress(): Flow<List<ProgressEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<ProgressEntity>> = flowOf(emptyList())
        override suspend fun upsert(progress: ProgressEntity) {}
        override suspend fun delete(bookId: String) {}
    }

    private class PlaySource(
        override val type: ServerSource,
        private val start: suspend () -> PlaybackSession?,
        private val close: suspend () -> Unit = {},
    ) : LibrarySource {
        val started = mutableListOf<Pair<ServerConfig, String>>()
        val closed = mutableListOf<Triple<String, Long, Long>>()
        override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> = emptyList()
        override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = emptyList()
        override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()
        override suspend fun startPlayback(server: ServerConfig, bookId: String): PlaybackSession? {
            started += server to bookId
            return start()
        }
        override suspend fun closePlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long) {
            closed += Triple(sessionId, positionMs, listenedMs)
            close()
        }
    }

    private val session = PlaybackSession("sess1", "srv1", "abs_a", 1_000, 0, emptyList(), emptyList())

    private fun entity(id: String, serverId: String = "srv1") =
        BookEntity(id, "T$id", "A", "N", 1L, "[]", "u", null, "ABS", serverId, 0L)

    private fun server(id: String = "srv1") = ServerConfig(id, ServerSource.ABS, "S", "http://s.test", "tok-secret")

    @Test
    fun `a start that cannot reach the server retries once on the address the connection check switched to`() = runTest {
        val plex = server().copy(source = ServerSource.PLEX, baseUrl = "https://a.plex.direct:32400")
        val switched = plex.copy(baseUrl = "http://192.0.2.11:32400")
        var calls = 0
        val source = PlaySource(ServerSource.PLEX, { if (calls++ == 0) throw java.net.UnknownHostException("a.plex.direct") else session })
        val failures = mutableListOf<Throwable>()
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(entity("abs_a"))), NoProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(listOf(plex)), listOf(source), pendingDao = InMemoryPendingProgressDao(), bookmarkDao = InMemoryBookmarkDao(),
            historyDao = InMemoryHistoryDao(),
            onConnectionFailure = { _, e -> failures += e; switched }
        )

        val result = repo.startPlayback("abs_a")

        assertSame(session, result.getOrThrow())
        assertEquals(listOf(plex.baseUrl, switched.baseUrl), source.started.map { it.first.baseUrl })
        assertIs<java.net.UnknownHostException>(failures.single())
    }

    @Test
    fun `with no other address the start fails as before`() = runTest {
        val source = PlaySource(ServerSource.ABS, { throw IOException("down") })
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(entity("abs_a"))), NoProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(listOf(server())), listOf(source), pendingDao = InMemoryPendingProgressDao(), bookmarkDao = InMemoryBookmarkDao(),
            historyDao = InMemoryHistoryDao(),
            onConnectionFailure = { _, _ -> null }
        )

        assertIs<IOException>(repo.startPlayback("abs_a").exceptionOrNull())
        assertEquals(1, source.started.size)
    }

    private fun repo(source: LibrarySource, servers: List<ServerConfig> = listOf(server()), books: List<BookEntity> = listOf(entity("abs_a"))) =
        BookRepositoryImpl(
            FakeBookDao(books), NoProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(servers), listOf(source), pendingDao = InMemoryPendingProgressDao(), bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao()
        )

    @Test
    fun `start gives the session for the book on its server`() = runTest {
        val source = PlaySource(ServerSource.ABS, { session })

        assertEquals(session, repo(source).startPlayback("abs_a").getOrThrow())
        assertEquals(listOf(server() to "abs_a"), source.started)
    }

    @Test
    fun `an unknown book fails and asks nobody`() = runTest {
        val source = PlaySource(ServerSource.ABS, { session })

        assertIs<IllegalStateException>(repo(source).startPlayback("abs_missing").exceptionOrNull())
        assertTrue(source.started.isEmpty())
    }

    @Test
    fun `a gone server fails without secrets`() = runTest {
        val source = PlaySource(ServerSource.ABS, { session })

        val error = assertIs<IllegalStateException>(
            repo(source, books = listOf(entity("abs_a", "gone"))).startPlayback("abs_a").exceptionOrNull()
        )
        assertTrue("tok-secret" !in error.message.orEmpty() && "s.test" !in error.message.orEmpty())
        assertTrue(source.started.isEmpty())
    }

    @Test
    fun `a source that cannot play fails safely`() = runTest {
        val error = assertIs<IllegalStateException>(repo(PlaySource(ServerSource.ABS, { null })).startPlayback("abs_a").exceptionOrNull())
        assertTrue("tok-secret" !in error.message.orEmpty())
    }

    @Test
    fun `a missing source for the server type fails`() = runTest {
        assertIs<IllegalStateException>(repo(PlaySource(ServerSource.PLEX, { session })).startPlayback("abs_a").exceptionOrNull())
    }

    @Test
    fun `a failing call is returned`() = runTest {
        val failure = IOException("HTTP 500")
        assertEquals(failure, repo(PlaySource(ServerSource.ABS, { throw failure })).startPlayback("abs_a").exceptionOrNull())
    }

    @Test
    fun `cancellation propagates from start and close`() = runTest {
        val source = PlaySource(ServerSource.ABS, { throw CancellationException("stop") }, { throw CancellationException("stop") })

        assertFailsWith<CancellationException> { repo(source).startPlayback("abs_a") }
        assertFailsWith<CancellationException> { repo(source).closePlayback(session, 1, 1) }
    }

    @Test
    fun `close passes the numbers and never throws`() = runTest {
        val ok = PlaySource(ServerSource.ABS, { session })
        assertTrue(repo(ok).closePlayback(session, 5_000, 2_000).isSuccess)
        assertEquals(listOf(Triple("sess1", 5_000L, 2_000L)), ok.closed)

        val failing = PlaySource(ServerSource.ABS, { session }, { throw IOException("down") })
        assertTrue(repo(failing).closePlayback(session, 1, 1).isFailure)
        assertTrue(repo(ok, servers = emptyList()).closePlayback(session, 1, 1).isFailure)
    }

    @Test
    fun `a sign-in problem from the source reaches the caller as the same instance`() = runTest {
        val signIn = SignInRequiredException("S")
        val source = PlaySource(ServerSource.ABS, { throw signIn }, { throw signIn })

        assertSame(signIn, repo(source).startPlayback("abs_a").exceptionOrNull())
        assertSame(signIn, repo(source).closePlayback(session, 1, 1).exceptionOrNull())
    }

    @Test
    fun `close never sends a negative position or listened time`() = runTest {
        val source = PlaySource(ServerSource.ABS, { session })
        repo(source).closePlayback(session, -5, -7)
        assertEquals(listOf(Triple("sess1", 0L, 0L)), source.closed)
    }
}
