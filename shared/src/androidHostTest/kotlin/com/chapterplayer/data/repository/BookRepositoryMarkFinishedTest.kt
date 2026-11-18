package com.chapterplayer.data.repository

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.domain.playback.MirrorPeer
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
import kotlin.test.assertFalse
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

    /**
     * A tiny Audiobookshelf: one progress record per item, the PATCH behaviour of its `applyProgressUpdate` (verified on a
     * real 2.37.1 server and in its source): set finished keeps the time unless one is sent; set not finished resets the
     * time to 0 whatever was sent; a finished item whose time moves away from the end (10 s) is un-finished.
     */
    private class MockAbs {
        private val FIRST_ITEM = "li_1"
        /** Every request received. Copies of a book are marked in parallel, so the list is thread-safe. */
        val requests: MutableList<HttpRequestData> = java.util.Collections.synchronizedList(mutableListOf())
        var down = false
        /** One record per item id, as on a real server: current time in seconds, finished, last update in ms. */
        private val records = java.util.concurrent.ConcurrentHashMap<String, Triple<Double, Boolean, Long>>()
        /** The record of the first item ("li_1"): current time in seconds, finished, last update in ms; null = never started (404). */
        fun recordOf(item: String): Triple<Double, Boolean, Long>? = records[item]
        fun setRecord(item: String, value: Triple<Double, Boolean, Long>) { records[item] = value }
        var record: Triple<Double, Boolean, Long>?
            get() = records[FIRST_ITEM]
            set(value) { if (value == null) records.remove(FIRST_ITEM) else records[FIRST_ITEM] = value }
        /** When set, the GET answers this instead of the record (a server that keeps a finished book's old place). */
        var answer: Triple<Double, Boolean, Long>? = null
        var serverClock = 5_000L
        /** When set, PATCHes after this many fail with a dropped connection (none of them reaches the record). */
        var failAfterPatches: Int? = null
        private val patchCount = java.util.concurrent.atomic.AtomicInteger()
        /** The times (seconds) of every session report the server received. */
        val syncs = mutableListOf<Double>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")

        /** The item a progress URL names (`api/me/progress/{id}`). */
        private fun itemOf(request: HttpRequestData) = request.url.encodedPath.substringAfterLast('/')

        val engine = MockEngine { request ->
            requests += request
            if (down) throw IOException("offline")
            val item = itemOf(request)
            when (request.method) {
                HttpMethod.Patch -> synchronized(this@MockAbs) {
                    failAfterPatches?.let { limit -> if (patchCount.getAndIncrement() >= limit) throw IOException("dropped") }
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val finished = body["isFinished"]!!.jsonPrimitive.content.toBoolean()
                    val sent = body["currentTime"]?.jsonPrimitive?.content?.toDouble()
                    val old = records[item]
                    val time = old?.first ?: 0.0
                    val unmarking = !finished && old?.second == true
                    val newTime = if (unmarking) 0.0 else sent ?: time
                    val stillDone = finished && !(newTime != time && 9_000.0 - newTime >= 10.0)
                    records[item] = Triple(newTime, stillDone, serverClock)
                    respond("OK", HttpStatusCode.OK, json)
                }
                HttpMethod.Get -> {
                    val r = answer ?: records[item]
                    if (r == null) respond("{}", HttpStatusCode.NotFound, json)
                    else respond(
                        """{"libraryItemId":"li_1","duration":9000.0,"progress":${if (r.second) 1.0 else r.first / 9000.0},"currentTime":${r.first},"isFinished":${r.second},"lastUpdate":${r.third}}""",
                        HttpStatusCode.OK, json
                    )
                }
                HttpMethod.Post -> {
                    // a session report: sets the time; a finished item whose time moves away from the end is un-finished
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val sent = body["currentTime"]!!.jsonPrimitive.content.toDouble()
                    syncs += sent
                    val old = records[FIRST_ITEM] ?: Triple(0.0, false, 0L)
                    val stillDone = old.second && !(sent != old.first && 9_000.0 - sent >= 10.0)
                    records[FIRST_ITEM] = Triple(sent, stillDone, serverClock)
                    respond("OK", HttpStatusCode.OK, json)
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
        loaded: String? = null,
        rewound: MutableList<String> = mutableListOf(),
        onRewind: suspend (String) -> Unit = {},
        books: List<BookEntity> = listOf(book()),
        peers: Map<String, List<MirrorPeer>> = emptyMap(),
    ): Fixture {
        val abs = MockAbs()
        val api = AbsApiService(HttpClient(abs.engine) { installApiDefaults(isDebuggable = false) })
        val pendingDao = InMemoryPendingProgressDao()
        val now = longArrayOf(1_000L)
        val kicks = intArrayOf(0)
        val repo = BookRepositoryImpl(
            FakeBookDao(books), progressView(progressDao), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(listOf(server)), listOf(AbsLibrarySource(api, PassThroughAuthorizer)),
            pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(),
            clock = { now[0] }, onQueued = { kicks[0]++ },
            mirrorPeers = object : ProgressMirrorPeers {
                override suspend fun peersOf(bookId: String): List<MirrorPeer> = peers[bookId].orEmpty()
            },
            loadedBookId = { loaded },
            rewindLoaded = {
                rewound += it
                onRewind(it)
            },
        )
        return Fixture(repo, abs, progressDao, pendingDao, now, kicks)
    }

    /** What the flusher does with each queued row (the server is reached at once here). */
    private suspend fun deliverQueue(f: Fixture) {
        for (row in f.repo.pendingProgress()) {
            f.repo.pushProgress(row).getOrThrow()
            f.repo.deletePending(row.bookId, row.updatedAtMs)
        }
    }

    private fun patches(f: Fixture) = f.abs.requests.filter { it.method == HttpMethod.Patch }
    private fun patchBody(r: HttpRequestData) = Json.parseToJsonElement((r.body as TextContent).text).jsonObject

    @Test
    fun `marking finished updates the book locally at zero, patches the server and adopts its answer`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.abs.record = Triple(4_000.0, false, 50L)

        val outcome = f.repo.markFinished("abs_li_1", true)

        assertEquals(MarkOutcome.SYNCED, outcome.getOrThrow())
        val (reset, finish) = patches(f).also { assertEquals(2, it.size) }
        assertEquals("/api/me/progress/li_1", finish.url.encodedPath)
        assertEquals("false", patchBody(reset)["isFinished"]!!.jsonPrimitive.content)
        assertEquals("true", patchBody(finish)["isFinished"]!!.jsonPrimitive.content)
        // the server ends finished at 0 (the mock un-finishes on any time moved away from the end, like Audiobookshelf)
        assertEquals(0.0, f.abs.record!!.first)
        assertEquals(true, f.abs.record!!.second)
        val row = f.progressDao.get("abs_li_1")!!
        assertTrue(row.isFinished)
        assertEquals(0L, row.positionMs) // started over
        assertEquals(1_000L, row.lastPlayedAt)
        assertTrue(f.pendingDao.rows.value.isEmpty())
    }

    @Test
    fun `a finished answer at the old place does not bring the place back`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.abs.answer = Triple(4_000.0, true, 6_000L)

        f.repo.markFinished("abs_li_1", true).getOrThrow()

        val row = f.progressDao.get("abs_li_1")!!
        assertTrue(row.isFinished)
        assertEquals(0L, row.positionMs)
    }

    @Test
    fun `a later sync of a finished server row at the old place leaves the book at zero`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.repo.markFinished("abs_li_1", true).getOrThrow()

        f.now[0] = 9_000L
        f.repo.saveServerProgress("abs_li_1", com.chapterplayer.domain.playback.ServerProgress(4_000_000L, 9_000_000L, true, 8_000L))

        val row = f.progressDao.get("abs_li_1")!!
        assertTrue(row.isFinished)
        assertEquals(0L, row.positionMs)
    }

    @Test
    fun `marking the loaded book finished rewinds the player and its old place is not saved back`() = runTest {
        val rewound = mutableListOf<String>()
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1", rewound = rewound)

        f.repo.markFinished("abs_li_1", true).getOrThrow()
        assertEquals(listOf("abs_li_1"), rewound)

        // the saver still reports the place from before the pause and seek
        f.now[0] = 1_400L
        f.repo.savePlaybackProgress(live(4_000_500L, 1_400L), loadedSinceMs = 900L)
        var row = f.progressDao.get("abs_li_1")!!
        assertEquals(0L, row.positionMs)
        assertTrue(row.isFinished)

        // then the player's place after the rewind keeps the mark
        f.now[0] = 1_500L
        f.repo.savePlaybackProgress(live(0L, 1_500L), loadedSinceMs = 900L)
        row = f.progressDao.get("abs_li_1")!!
        assertTrue(row.isFinished)

        // listening again ends the finished state, here and on the server
        f.now[0] = 2_000L
        f.repo.savePlaybackProgress(live(30_000L, 2_000L), loadedSinceMs = 900L)
        row = f.progressDao.get("abs_li_1")!!
        assertEquals(30_000L, row.positionMs)
        assertFalse(row.isFinished)
        // the server hears it through the queue (the saver never waits for a server)
        assertEquals(false, f.pendingDao.rows.value.single().markedFinished)
        deliverQueue(f)
        assertEquals(Triple(30.0, false, 5_000L), f.abs.record)
    }

    @Test
    fun `a place saved after the grace is listening again`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1")
        f.repo.markFinished("abs_li_1", true).getOrThrow()

        f.now[0] = 1_000L + com.chapterplayer.domain.playback.MARK_REWIND_GRACE_MS + 1
        f.repo.savePlaybackProgress(live(60_000L, f.now[0]), loadedSinceMs = 900L)

        val row = f.progressDao.get("abs_li_1")!!
        assertEquals(60_000L, row.positionMs)
        assertFalse(row.isFinished)
    }

    @Test
    fun `a finished book played again without a mark of this session is no longer finished here and on the server`() = runTest {
        // heard to the end earlier (or finished elsewhere): Play again from the start, then the first save while playing
        val f = fixture(listOf(ProgressEntity("abs_li_1", 9_000_000L, 9_000_000L, 100L, true)), loaded = "abs_li_1")
        f.abs.record = Triple(9_000.0, true, 50L)

        f.now[0] = 2_000L
        f.repo.savePlaybackProgress(live(10_000L, 2_000L), loadedSinceMs = 1_500L)

        val row = f.progressDao.get("abs_li_1")!!
        assertFalse(row.isFinished)
        assertEquals(10_000L, row.positionMs)
        assertTrue(patches(f).isEmpty()) // nothing inline
        deliverQueue(f)
        // two patches: not finished (the finished item is reset to 0), then the place heard, which the server keeps
        assertEquals(listOf("false", "false"), patches(f).map { patchBody(it)["isFinished"]!!.jsonPrimitive.content })
        assertEquals(Triple(10.0, false, 5_000L), f.abs.record)
        // a save of a book that was not finished queues nothing more
        f.repo.savePlaybackProgress(live(20_000L, 3_000L), loadedSinceMs = 1_500L)
        assertTrue(f.pendingDao.rows.value.isEmpty())
    }

    @Test
    fun `a save at the very start of a finished book changes nothing on the server and keeps it finished`() = runTest {
        // heard to the end; Play then Pause after a second (details, the car or a widget)
        val f = fixture(listOf(ProgressEntity("abs_li_1", 9_000_000L, 9_000_000L, 100L, true)), loaded = "abs_li_1")

        f.repo.savePlaybackProgress(live(1_000L, 2_000L), loadedSinceMs = 1_500L)

        assertTrue(f.progressDao.get("abs_li_1")!!.isFinished)
        assertTrue(f.pendingDao.rows.value.isEmpty())
        assertTrue(f.abs.requests.isEmpty())
    }

    @Test
    fun `the reopen is queued with the save so a cancelled saver loses nothing`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 0L, 9_000_000L, 100L, true)), loaded = "abs_li_1")
        f.abs.down = true // a server that would keep an inline push waiting

        val saver = launch { f.repo.savePlaybackProgress(live(10_000L, 2_000L), loadedSinceMs = 1_500L) }
        testScheduler.runCurrent()
        saver.cancel() // the pause cancels the saver's collectLatest
        saver.join()

        assertFalse(f.progressDao.get("abs_li_1")!!.isFinished)
        val queued = f.pendingDao.rows.value.single()
        assertEquals(false, queued.markedFinished)
        assertEquals(10_000L, queued.positionMs)
        assertTrue(f.abs.requests.isEmpty())
        assertEquals(1, f.kicks[0])
    }

    @Test
    fun `a failed rewind is tried once more and holds the mark only for a while`() = runTest {
        var calls = 0
        val f = fixture(
            listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1",
            onRewind = { calls++; throw IllegalStateException("no player") },
        )
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        assertEquals(2, calls)

        // the player was left at the old place and the person listens on there: past the limit that is listening
        f.now[0] = 1_000L + com.chapterplayer.domain.playback.MARK_REWIND_FAILED_LIMIT_MS + 1
        f.repo.savePlaybackProgress(live(4_100_000L, f.now[0]), loadedSinceMs = 900L)
        val row = f.progressDao.get("abs_li_1")!!
        assertFalse(row.isFinished)
        assertEquals(4_100_000L, row.positionMs)
    }

    @Test
    fun `a rewind that works on the second try leaves the normal rules`() = runTest {
        var calls = 0
        val f = fixture(
            listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1",
            onRewind = { if (calls++ == 0) throw IllegalStateException("busy") },
        )
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        assertEquals(2, calls)
        f.now[0] = 8_000L
        f.repo.savePlaybackProgress(live(20_000L, 8_000L), loadedSinceMs = 900L)
        assertFalse(f.progressDao.get("abs_li_1")!!.isFinished)
    }

    @Test
    fun `a reopen interrupted between its two patches loses nothing and the retry completes`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 0L, 9_000_000L, 100L, true)), loaded = "abs_li_1")
        f.abs.record = Triple(0.0, true, 50L)
        f.repo.savePlaybackProgress(live(30_000L, 2_000L), loadedSinceMs = 1_500L)
        val queued = f.repo.pendingProgress().single()

        // the first patch reaches the server, the second does not
        f.abs.failAfterPatches = 1
        assertTrue(f.repo.pushProgress(queued).isFailure)
        assertEquals(Triple(0.0, false, 5_000L), f.abs.record)

        // the flusher tries again: the server's newer row at 0 and not finished does not drop the queued place
        f.abs.failAfterPatches = null
        f.abs.serverClock = 6_000L
        val server = f.repo.fetchServerProgress("abs_li_1").getOrThrow()
        assertEquals(com.chapterplayer.domain.playback.FlushDecision.SEND, com.chapterplayer.domain.playback.flushDecision(queued, server))
        f.repo.pushProgress(queued).getOrThrow()
        assertEquals(Triple(30.0, false, 6_000L), f.abs.record)
    }

    @Test
    fun `a mark finished interrupted between its two patches is queued and the retry completes it`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.abs.record = Triple(4_000.0, false, 50L)

        // the first patch (not finished at 0) reaches the server, the second (finished) does not
        f.abs.failAfterPatches = 1
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        assertEquals(Triple(0.0, false, 5_000L), f.abs.record)
        val queued = f.repo.pendingProgress().single()
        assertEquals(true, queued.markedFinished)

        // the flusher tries again: the server's row is newer than the mark but it is only the mark's own first half
        f.abs.failAfterPatches = null
        f.abs.serverClock = 6_000L
        val server = f.repo.fetchServerProgress("abs_li_1").getOrThrow()
        assertEquals(com.chapterplayer.domain.playback.FlushDecision.SEND, com.chapterplayer.domain.playback.flushDecision(queued, server))
        f.repo.pushProgress(queued).getOrThrow()
        assertEquals(true, f.abs.record!!.second)
        assertEquals(0.0, f.abs.record!!.first)
    }

    @Test
    fun `live reports keep a queued reopen newest so it is still delivered`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 0L, 9_000_000L, 100L, true)), loaded = "abs_li_1")
        f.abs.record = Triple(0.0, true, 50L)
        f.now[0] = 2_000L
        f.repo.savePlaybackProgress(live(30_000L, 2_000L), loadedSinceMs = 1_500L)
        // the first flush fails and backs off
        f.abs.down = true
        assertTrue(f.repo.pushProgress(f.repo.pendingProgress().single()).isFailure)
        f.abs.down = false

        // live reports go on; the server's time moves on while it still reads the book as finished
        f.now[0] = 12_000L
        f.repo.syncPlayback(session, 40_000L, 10_000L, isPlaying = true).getOrThrow()
        f.abs.record = Triple(0.0, true, 11_000L)

        val queued = f.repo.pendingProgress().single()
        assertEquals(12_000L, queued.updatedAtMs)
        val server = f.repo.fetchServerProgress("abs_li_1").getOrThrow()
        assertEquals(com.chapterplayer.domain.playback.FlushDecision.SEND, com.chapterplayer.domain.playback.flushDecision(queued, server))
        f.repo.pushProgress(queued).getOrThrow()
        assertEquals(false, f.abs.record!!.second)
        assertEquals(30.0, f.abs.record!!.first)
    }

    @Test
    fun `a copy played again reopens a finished other copy at the place heard`() = runTest {
        val f = fixture(
            listOf(ProgressEntity("abs_li_1", 0L, 9_000_000L, 100L, true), ProgressEntity("abs_li_2", 0L, 9_000_000L, 100L, true)),
            loaded = "abs_li_1", books = listOf(book(), book("abs_li_2")),
            peers = mapOf("abs_li_1" to listOf(MirrorPeer("abs_li_2", "srv1", 9_000_000L))),
        )
        f.abs.setRecord("li_2", Triple(0.0, true, 50L)) // copy B's record: finished

        f.now[0] = 2_000L
        f.repo.savePlaybackProgress(live(30_000L, 2_000L), loadedSinceMs = 1_500L)

        val rows = f.repo.pendingProgress().associateBy { it.bookId }
        assertEquals(false, rows.getValue("abs_li_1").markedFinished)
        val copy = rows.getValue("abs_li_2")
        assertEquals(false, copy.markedFinished)
        assertEquals(30_000L, copy.positionMs)
        // delivered as an explicit not finished: two patches, so the finished item keeps the place instead of 0
        f.repo.pushProgress(copy).getOrThrow()
        assertEquals(2, patches(f).size)
        assertEquals(Triple(30.0, false, 5_000L), f.abs.recordOf("li_2"))
    }

    @Test
    fun `an ordinary queued place is one patch`() = runTest {
        val f = fixture()
        f.repo.pushProgress(com.chapterplayer.domain.playback.PendingProgress("abs_li_1", "srv1", 30_000L, 9_000_000L, 1_000L, 2_000L)).getOrThrow()
        assertEquals(1, patches(f).size)
    }

    @Test
    fun `a finished book played for a second then paused tells no server`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 9_000_000L, 9_000_000L, 100L, true)), loaded = "abs_li_1")
        f.repo.savePlaybackProgress(live(1_000L, 2_000L), loadedSinceMs = 1_500L)
        f.repo.syncPlayback(session, 1_000L, 1_000L, isPlaying = false).getOrThrow()
        f.repo.closePlayback(session, 1_000L, 1_000L).getOrThrow()
        assertTrue(f.abs.requests.isEmpty())
        // real listening is reported as always
        f.repo.syncPlayback(session, 30_000L, 30_000L, isPlaying = true).getOrThrow()
        assertEquals(listOf(30.0), f.abs.syncs)
    }

    @Test
    fun `a book played again offline then heard to the end is sent finished`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 0L, 9_000_000L, 100L, true)), loaded = "abs_li_1")
        f.abs.down = true
        f.repo.savePlaybackProgress(live(10_000L, 2_000L), loadedSinceMs = 1_500L)
        // the syncer queues the place at the end, later
        f.repo.queueProgress(com.chapterplayer.domain.playback.PendingProgress("abs_li_1", "srv1", 8_999_000L, 9_000_000L, 60_000L, 9_000L))

        val queued = f.pendingDao.rows.value.single()
        assertNull(queued.markedFinished)
        f.abs.down = false
        deliverQueue(f)
        assertEquals(true, f.abs.record!!.second)
    }

    @Test
    fun `a failed rewind keeps the mark until the player is seen at the start`() = runTest {
        val f = fixture(
            listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1",
            onRewind = { throw IllegalStateException("no player") },
        )
        f.repo.markFinished("abs_li_1", true).getOrThrow()

        // after the grace the player still reports the old place: the mark holds, nothing is reported or queued
        f.now[0] = 20_000L
        f.repo.savePlaybackProgress(live(4_050_000L, 20_000L), loadedSinceMs = 900L)
        f.repo.syncPlayback(session, 4_050_000L, 10_000L, isPlaying = true).getOrThrow()
        var row = f.progressDao.get("abs_li_1")!!
        assertTrue(row.isFinished)
        assertEquals(0L, row.positionMs)
        assertTrue(f.abs.syncs.isEmpty())
        assertTrue(f.pendingDao.rows.value.isEmpty())

        // the person moves to the start: from then on listening again ends the mark
        f.now[0] = 21_000L
        f.repo.savePlaybackProgress(live(500L, 21_000L), loadedSinceMs = 900L)
        assertTrue(f.progressDao.get("abs_li_1")!!.isFinished)
        f.now[0] = 25_000L
        f.repo.savePlaybackProgress(live(20_000L, 25_000L), loadedSinceMs = 900L)
        row = f.progressDao.get("abs_li_1")!!
        assertFalse(row.isFinished)
        assertEquals(20_000L, row.positionMs)
    }

    @Test
    fun `an unreachable server gets the book reopened through the queue`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 0L, 9_000_000L, 100L, true)), loaded = "abs_li_1")
        f.abs.down = true

        f.repo.savePlaybackProgress(live(10_000L, 2_000L), loadedSinceMs = 1_500L)

        val queued = f.pendingDao.rows.value.single()
        assertEquals(false, queued.markedFinished)
        assertEquals(10_000L, queued.positionMs)
    }

    @Test
    fun `listening again on one copy reopens the other copies too`() = runTest {
        val f = fixture(
            listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false), ProgressEntity("abs_li_2", 4_100_000L, 9_000_000L, 120L, false)),
            loaded = "abs_li_1", books = listOf(book(), book("abs_li_2")),
            peers = mapOf("abs_li_1" to listOf(MirrorPeer("abs_li_2", "srv1", 9_000_000L)), "abs_li_2" to listOf(MirrorPeer("abs_li_1", "srv1", 9_000_000L))),
        )
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        assertTrue(f.progressDao.get("abs_li_2")!!.isFinished)

        f.now[0] = 20_000L
        f.repo.savePlaybackProgress(live(30_000L, 20_000L), loadedSinceMs = 900L)

        val other = f.progressDao.get("abs_li_2")!!
        assertFalse(other.isFinished)
        assertEquals(30_000L, other.positionMs)
        assertTrue(other.mirrored)
    }

    @Test
    fun `a book that is not loaded and a not finished mark do not touch the player`() = runTest {
        val rewound = mutableListOf<String>()
        val other = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_other", rewound = rewound)
        other.repo.markFinished("abs_li_1", true).getOrThrow()
        assertTrue(rewound.isEmpty())

        val same = fixture(listOf(ProgressEntity("abs_li_1", 9_000_000L, 9_000_000L, 100L, true)), loaded = "abs_li_1", rewound = rewound)
        same.repo.markFinished("abs_li_1", false).getOrThrow()
        assertTrue(rewound.isEmpty())
    }

    @Test
    fun `marking a book finished rewinds the player holding another copy and both copies start over`() = runTest {
        val rewound = mutableListOf<String>()
        val f = fixture(
            listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false), ProgressEntity("abs_li_2", 4_100_000L, 9_000_000L, 120L, false)),
            loaded = "abs_li_2", rewound = rewound, books = listOf(book(), book("abs_li_2")),
            peers = mapOf("abs_li_1" to listOf(MirrorPeer("abs_li_2", "srv1", 9_000_000L))),
        )

        f.repo.markFinished("abs_li_1", true).getOrThrow()

        assertEquals(listOf("abs_li_2"), rewound, "rewound=$rewound")
        for (id in listOf("abs_li_1", "abs_li_2")) {
            val row = f.progressDao.get(id)!!
            assertTrue(row.isFinished, "$id row=$row")
            assertEquals(0L, row.positionMs, "$id row=$row")
        }
        // the loaded copy's old place reported once more stays at 0
        f.now[0] = 1_200L
        f.repo.savePlaybackProgress(live(4_100_500L, 1_200L).copy(bookId = "abs_li_2"), loadedSinceMs = 900L)
        assertEquals(0L, f.progressDao.get("abs_li_2")!!.positionMs, "after the stale save row=${f.progressDao.get("abs_li_2")}")
    }

    @Test
    fun `an offline mark of the loaded book sends zero even when the old place is queued after it`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1")
        f.abs.down = true
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        f.now[0] = 1_100L
        f.repo.queueProgress(com.chapterplayer.domain.playback.PendingProgress("abs_li_1", "srv1", 4_000_500L, 9_000_000L, 1_000L, 1_100L))

        val queued = f.pendingDao.rows.value.single()
        assertEquals(true, queued.markedFinished)
        assertEquals(0L, queued.positionMs)
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

        assertEquals(2, patches(f).size) // reset to 0, then finished without a time
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

    private val session = com.chapterplayer.data.playback.PlaybackSession("sess1", "srv1", "abs_li_1", 9_000_000L, 0L, emptyList(), emptyList())

    @Test
    fun `the pause report at the old place sent between the mark and the patches never reaches the server`() = runTest {
        lateinit var f: Fixture
        f = fixture(
            listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1",
            // the player pauses: the syncer's report of the place before the seek goes out before the patches
            onRewind = { f.repo.syncPlayback(session, 4_000_500L, 1_000L, isPlaying = false).getOrThrow() },
        )
        f.abs.record = Triple(4_000.0, false, 50L)

        f.repo.markFinished("abs_li_1", true).getOrThrow()

        assertTrue(f.abs.syncs.isEmpty())
        assertEquals(Triple(0.0, true, 5_000L), f.abs.record)
        // the same report arriving after the patches is held back too, and so is the pause at the start
        f.now[0] = 1_300L
        f.repo.syncPlayback(session, 4_000_500L, 1_000L, isPlaying = false).getOrThrow()
        f.now[0] = 1_500L
        f.repo.syncPlayback(session, 300L, 0L, isPlaying = false).getOrThrow()
        assertTrue(f.abs.syncs.isEmpty())
        assertEquals(true, f.abs.record!!.second)
    }

    @Test
    fun `listening again after the mark reports again and may un-finish the book on the server`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1")
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        f.now[0] = 1_200L
        f.repo.syncPlayback(session, 500L, 0L, isPlaying = false).getOrThrow()
        assertTrue(f.abs.syncs.isEmpty())

        // Play: the first report while playing is listening again; it goes out and the silence ends
        f.now[0] = 12_000L
        f.repo.syncPlayback(session, 10_000L, 10_000L, isPlaying = true).getOrThrow()
        f.now[0] = 13_000L
        f.repo.syncPlayback(session, 11_000L, 1_000L, isPlaying = false).getOrThrow()

        assertEquals(listOf(10.0, 11.0), f.abs.syncs)
        assertEquals(false, f.abs.record!!.second) // the server un-finishes it as Audiobookshelf does
        // the report ended the mark: the next save stores the book as not finished
        f.repo.savePlaybackProgress(live(11_000L, 13_000L), loadedSinceMs = 900L)
        assertFalse(f.progressDao.get("abs_li_1")!!.isFinished)
    }

    @Test
    fun `a save no longer kept as finished ends the silence`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1")
        f.repo.markFinished("abs_li_1", true).getOrThrow()

        // the book was loaded again after the mark: the save is the player's own, not the mark's
        f.now[0] = 9_000L
        f.repo.savePlaybackProgress(live(10_000L, 9_000L), loadedSinceMs = 8_000L)
        f.repo.syncPlayback(session, 10_000L, 1_000L, isPlaying = false).getOrThrow()

        assertEquals(listOf(10.0), f.abs.syncs)
    }

    @Test
    fun `a failed rewind keeps the mark and logs one line with the book id only`() = runTest {
        com.chapterplayer.data.diagnostics.ProblemLog.clear()
        val f = fixture(
            listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)), loaded = "abs_li_1",
            onRewind = { throw IllegalStateException("player gone http://abs.test/ tok-secret") },
        )

        assertEquals(MarkOutcome.SYNCED, f.repo.markFinished("abs_li_1", true).getOrThrow())

        assertEquals(0L, f.progressDao.get("abs_li_1")!!.positionMs)
        val lines = com.chapterplayer.data.diagnostics.ProblemLog.snapshot()
        assertTrue("Rewind after Mark as finished failed for abs_li_1: IllegalStateException" in lines, lines.toString())
        assertTrue(lines.none { "tok-secret" in it || "abs.test" in it }) // the error's message is never logged
        com.chapterplayer.data.diagnostics.ProblemLog.clear()
    }

    @Test
    fun `a book that was not loaded when marked reports as always`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.repo.markFinished("abs_li_1", true).getOrThrow()
        f.now[0] = 1_100L
        f.repo.syncPlayback(session, 30_000L, 0L, isPlaying = false).getOrThrow()
        assertEquals(listOf(30.0), f.abs.syncs)
    }

    @Test
    fun `the player's save keeps a mark made while the book was loaded at the start, and a later not finished mark clears it`() = runTest {
        val f = fixture(listOf(ProgressEntity("abs_li_1", 4_000_000L, 9_000_000L, 100L, false)))
        f.now[0] = 1_000L
        f.repo.markFinished("abs_li_1", true).getOrThrow()

        // at the start (paused after the rewind) the mark holds
        f.repo.savePlaybackProgress(live(500L, 1_500L), loadedSinceMs = 900L)
        assertEquals(true, f.progressDao.get("abs_li_1")!!.isFinished)
        f.repo.savePlaybackProgress(live(1_000L, 1_600L), loadedSinceMs = 900L)
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
        f.repo.savePlaybackProgress(live(10_000L, 1_500L), loadedSinceMs = 900L)
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
