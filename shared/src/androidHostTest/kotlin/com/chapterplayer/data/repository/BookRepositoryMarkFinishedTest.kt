package com.chapterplayer.data.repository

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.source.AbsLibrarySource
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.testing.FakeServerList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Mark as finished / not finished" through the real Audiobookshelf source on a mock server. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryMarkFinishedTest {

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

    private object PassThroughAuthorizer : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            call("Bearer " + server.token)
    }

    /** A tiny Audiobookshelf: one progress record per item, the PATCH behaviour verified on a real 2.37.1 server. */
    private class MockAbs {
        val requests = mutableListOf<HttpRequestData>()
        var down = false
        /** The server's record: current time in seconds, finished, last update in ms; null = never started (404). */
        var record: Triple<Double, Boolean, Long>? = null
        var serverClock = 5_000L
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        val engine = MockEngine { request ->
            requests += request
            if (down) throw IOException("offline")
            when (request.method) {
                HttpMethod.Patch -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val finished = body["isFinished"]!!.jsonPrimitive.content.toBoolean()
                    val sent = body["currentTime"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
                    // finished keeps the time; not finished resets the item, whatever time was sent
                    record = Triple(if (finished) sent else 0.0, finished, serverClock)
                    respond("OK", HttpStatusCode.OK, json)
                }
                HttpMethod.Get -> {
                    val r = record
                    if (r == null) respond("{}", HttpStatusCode.NotFound, json)
                    else respond(
                        """{"libraryItemId":"li_1","duration":9000.0,"progress":${if (r.second) 1.0 else r.first / 9000.0},"currentTime":${r.first},"isFinished":${r.second},"lastUpdate":${r.third}}""",
                        HttpStatusCode.OK, json
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
    }

    private val server = ServerConfig("srv1", ServerSource.ABS, "S", "http://abs.test/", "tok-secret")
    private fun book(id: String = "abs_li_1") = BookEntity(id, "T", "A", "N", 9_000_000L, "[]", "u", null, "ABS", "srv1", 0L)

    private class Fixture(
        val repo: BookRepositoryImpl,
        val abs: MockAbs,
        val progressDao: InMemoryProgressDao,
        val pendingDao: InMemoryPendingProgressDao,
        val now: LongArray,
        val kicks: IntArray,
    )

    private fun fixture(
        local: List<ProgressEntity> = emptyList(),
        progressDao: InMemoryProgressDao = InMemoryProgressDao(local),
        progressView: (InMemoryProgressDao) -> com.chapterplayer.data.local.dao.ProgressDao = { it },
    ): Fixture {
        val abs = MockAbs()
        val api = AbsApiService(HttpClient(abs.engine) { installApiDefaults(isDebuggable = false) })
        val pendingDao = InMemoryPendingProgressDao()
        val now = longArrayOf(1_000L)
        val kicks = intArrayOf(0)
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(book())), progressView(progressDao), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(listOf(server)), listOf(AbsLibrarySource(api, PassThroughAuthorizer)),
            pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(),
            clock = { now[0] }, onQueued = { kicks[0]++ }
        )
        return Fixture(repo, abs, progressDao, pendingDao, now, kicks)
    }

    private fun patches(f: Fixture) = f.abs.requests.filter { it.method == HttpMethod.Patch }
    private fun patchBody(r: HttpRequestData) = Json.parseToJsonElement((r.body as TextContent).text).jsonObject

    @Test
    fun `marking finished updates the book locally, patches the server and adopts its answer`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))

        val outcome = f.repo.markFinished("abs_li_1", true)

        assertEquals(MarkOutcome.SYNCED, outcome.getOrThrow())
        val patch = patches(f).single()
        assertEquals("/api/me/progress/li_1", patch.url.encodedPath)
        assertEquals("true", patchBody(patch)["isFinished"]!!.jsonPrimitive.content)
        val row = f.progressDao.get("abs_li_1")!!
        assertTrue(row.isFinished)
        assertEquals(4_000_000L, row.positionMs) // position kept
        assertEquals(1_000L, row.lastPlayedAt)
        assertTrue(f.pendingDao.rows.value.isEmpty())
    }

    @Test
    fun `marking not finished adopts the position 0 the server answers with`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 9_000_000L, 9_000_000L, 100L, true)))
        f.abs.record = Triple(9_000.0, true, 50L)

        f.repo.markFinished("abs_li_1", false).getOrThrow()

        assertEquals("false", patchBody(patches(f).single())["isFinished"]!!.jsonPrimitive.content)
        val row = f.progressDao.get("abs_li_1")!!
        assertEquals(0L, row.positionMs)
        assertEquals(false, row.isFinished)
    }

    @Test
    fun `a book the server has no progress for gets a row created by the patch`() = runTest {
        val f = fixture() // no local row, the server answers 404 until the patch creates it

        f.repo.markFinished("abs_li_1", true).getOrThrow()

        assertEquals(1, patches(f).size)
        val row = f.progressDao.get("abs_li_1")!!
        assertTrue(row.isFinished)
        assertEquals(9_000_000L, row.durationMs)
        assertEquals(0L, row.positionMs)
        assertEquals(true, f.abs.record!!.second)
    }

    @Test
    fun `an unreachable server keeps the local change and queues the mark`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.abs.down = true

        val outcome = f.repo.markFinished("abs_li_1", true)

        assertEquals(MarkOutcome.QUEUED, outcome.getOrThrow())
        assertTrue(f.progressDao.get("abs_li_1")!!.isFinished)
        val queued = f.pendingDao.rows.value.single()
        assertEquals(true, queued.markedFinished)
        assertEquals(1_000L, queued.updatedAtMs)
        assertEquals(1, f.kicks[0])
    }

    @Test
    fun `a queued mark is delivered by the flusher when the network returns, with its flag`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.abs.down = true
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        // a normal position queued afterwards must not drop the mark
        f.now[0] = 2_000L
        f.repo.queueProgress(com.chapterplayer.domain.playback.PendingProgress("abs_li_1", "srv1", 4_500_000L, 9_000_000L, 5_000L, 2_000L))
        assertEquals(true, f.pendingDao.rows.value.single().markedFinished)

        f.abs.down = false
        f.now[0] = 3_000L
        val online = MutableStateFlow(true)
        val flusher = PendingProgressFlusher(
            f.repo, object : ConnectivityMonitor { override val isOnline = online },
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), nowMs = { f.now[0] }
        )
        flusher.start()
        flusher.kick()
        testScheduler.advanceUntilIdle()
        // the mock engine answers on its own threads, so wait in real time for the queue to empty
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (f.pendingDao.rows.value.isNotEmpty()) delay(10) } }

        val patch = patches(f).last()
        assertEquals("true", patchBody(patch)["isFinished"]!!.jsonPrimitive.content)
        assertTrue(f.pendingDao.rows.value.isEmpty())
        assertEquals(true, f.abs.record!!.second)
    }

    @Test
    fun `a mark delivered earlier removes an older queued position`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.repo.queueProgress(com.chapterplayer.domain.playback.PendingProgress("abs_li_1", "srv1", 4_000_000L, 9_000_000L, 5_000L, 500L))

        f.repo.markFinished("abs_li_1", true).getOrThrow()

        assertTrue(f.pendingDao.rows.value.isEmpty())
    }

    private fun live(pos: Long, at: Long) = com.chapterplayer.data.model.ReadingProgress("abs_li_1", pos, 9_000_000L, at, false)

    @Test
    fun `the player's save keeps a mark made while the book was loaded, and a later not finished mark clears it`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.now[0] = 1_000L
        f.repo.markFinished("abs_li_1", true).getOrThrow()

        f.repo.savePlaybackProgress(live(4_100_000L, 1_500L), loadedSinceMs = 900L)
        assertEquals(true, f.progressDao.get("abs_li_1")!!.isFinished)
        f.repo.savePlaybackProgress(live(4_200_000L, 1_600L), loadedSinceMs = 900L)
        assertEquals(true, f.progressDao.get("abs_li_1")!!.isFinished)

        f.now[0] = 2_000L
        f.repo.markFinished("abs_li_1", false).getOrThrow()
        f.repo.savePlaybackProgress(live(10_000L, 2_500L), loadedSinceMs = 900L)
        val row = f.progressDao.get("abs_li_1")!!
        assertEquals(false, row.isFinished)
        assertEquals(10_000L, row.positionMs)
        f.repo.savePlaybackProgress(live(20_000L, 2_600L), loadedSinceMs = 900L)
        assertEquals(false, f.progressDao.get("abs_li_1")!!.isFinished)
    }

    @Test
    fun `a finished row that came from the server is not pinned by the player's save`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 9_000_000L, 9_000_000L, 1_200L, true)))
        f.repo.savePlaybackProgress(live(1_000L, 1_500L), loadedSinceMs = 900L)
        assertEquals(false, f.progressDao.get("abs_li_1")!!.isFinished)
    }

    @Test
    fun `a mark made between the save's read and its write is not lost`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val inner = InMemoryProgressDao(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        val slow = object : com.chapterplayer.data.local.dao.ProgressDao by inner {
            override suspend fun get(bookId: String): ProgressEntity? {
                val row = inner.get(bookId)
                // the first read (the player's save) is held up after it has read
                if (reads++ == 0) gate.await()
                return row
            }
        }
        val f = fixture(progressDao = inner, progressView = { slow })
        f.now[0] = 1_000L

        val save = launch { f.repo.savePlaybackProgress(live(4_100_000L, 1_000L), loadedSinceMs = 900L) }
        testScheduler.runCurrent()
        val mark = launch { f.repo.markFinished("abs_li_1", true).getOrThrow() }
        testScheduler.runCurrent()
        gate.complete(Unit)
        save.join()
        mark.join()

        assertTrue(inner.get("abs_li_1")!!.isFinished)
    }

    @Test
    fun `an unknown book fails and changes nothing`() = runTest {
        val f = fixture()
        assertTrue(f.repo.markFinished("abs_missing", true).isFailure)
        assertNull(f.progressDao.get("abs_missing"))
        assertTrue(f.abs.requests.isEmpty())
    }
}
