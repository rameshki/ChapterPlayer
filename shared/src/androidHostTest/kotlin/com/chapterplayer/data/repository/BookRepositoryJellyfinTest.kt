package com.chapterplayer.data.repository

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.PendingProgressEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.data.source.JellyfinLibrarySource
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinIsoTime
import com.chapterplayer.domain.jellyfin.jellyfinTimeMs
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeServerList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Queued positions, mirrors and "Mark as finished / not finished" for a Jellyfin book, through the real repository and the
 * real Jellyfin source on a tiny mock server that keeps one item's user data (J2, Task 12).
 */
class BookRepositoryJellyfinTest {

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

    /** One item of one hour; the user data follows the writes and marks like Jellyfin's (a mark played sets the place to 0). */
    private class MockJellyfin(val itemId: String) {
        val requests = mutableListOf<HttpRequestData>()
        @Volatile var down = false
        @Volatile var status = HttpStatusCode.OK
        @Volatile var failWrites = false
        var positionTicks = 0L
        var played = false
        var lastPlayed: String? = null
        val clockMs = 1_793_100_000_000L

        private fun userData() = buildString {
            append("""{"PlaybackPositionTicks":$positionTicks,"Played":$played,"PlayCount":${if (played) 1 else 0}""")
            lastPlayed?.let { append(""","LastPlayedDate":"$it"""") }
            append(""","Key":"$itemId","ItemId":"$itemId"}""")
        }

        val engine = MockEngine { request ->
            synchronized(requests) { requests += request }
            if (down) throw IOException("offline")
            if (status != HttpStatusCode.OK) return@MockEngine respond("", status)
            val path = request.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path == "/UserItems/$itemId/UserData" && request.method == HttpMethod.Get -> respond(userData(), HttpStatusCode.OK, json)
                path == "/UserItems/$itemId/UserData" && failWrites -> respond("", HttpStatusCode.ServiceUnavailable)
                path.startsWith("/Sessions/Playing") -> respond("", HttpStatusCode.NoContent)
                path == "/UserItems/$itemId/UserData" -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    positionTicks = body.getValue("PlaybackPositionTicks").jsonPrimitive.long
                    played = body.getValue("Played").jsonPrimitive.boolean
                    lastPlayed = body.getValue("LastPlayedDate").jsonPrimitive.content
                    respond(userData(), HttpStatusCode.OK, json)
                }
                path == "/UserPlayedItems/$itemId" && request.method == HttpMethod.Post -> {
                    played = true
                    positionTicks = 0L
                    lastPlayed = jellyfinIsoTime(clockMs)
                    respond(userData(), HttpStatusCode.OK, json)
                }
                path == "/UserPlayedItems/$itemId" && request.method == HttpMethod.Delete -> {
                    played = false
                    respond(userData(), HttpStatusCode.OK, json)
                }
                path == "/Items/$itemId" -> respond(
                    """{"Id":"$itemId","Name":"Quiet Rivers","Type":"AudioBook","RunTimeTicks":36000000000,""" +
                        """"MediaSources":[{"Id":"$itemId","Container":"mp3","RunTimeTicks":36000000000}]}""",
                    HttpStatusCode.OK, json,
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        /** Every request that is not a read, as "METHOD path". */
        fun writes() = synchronized(requests) { requests.filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" } }

        fun lastBody(): String = when (val c = synchronized(requests) { requests.last { it.method != HttpMethod.Get } }.body) {
            is TextContent -> c.text
            is OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> ""
        }
    }

    private val itemId = "9a4c1e7b3d2f60a8b5c4d3e2f1a09b8c"
    private val bookId = "jf_$itemId"
    private val hour = 3_600_000L
    private val server = ServerConfig("jf1", ServerSource.JELLYFIN, "J", "https://jf.test", "tok-secret")
    private val book = BookEntity(bookId, "Quiet Rivers", "A", "", hour, "[]", "", null, "JELLYFIN", "jf1", 0L)
    private val absCopy = BookEntity("abs_li_rivers", "Quiet Rivers", "A", "", hour, "[]", "u", null, "ABS", "abs1", 0L)

    private class Fixture(
        val repo: BookRepositoryImpl,
        val jf: MockJellyfin,
        val progressDao: InMemoryProgressDao,
        val pendingDao: InMemoryPendingProgressDao,
        val signedOut: MutableList<String>,
        val source: JellyfinLibrarySource,
    )

    private fun fixture(local: List<ProgressEntity> = emptyList(), now: Long = 1_793_000_000_000L): Fixture {
        val jf = MockJellyfin(itemId)
        val api = JellyfinApiService(HttpClient(jf.engine) { installApiDefaults(isDebuggable = false) }) { JellyfinClient("Android", it, "0.4.1") }
        val signedOut = mutableListOf<String>()
        val source = JellyfinLibrarySource(
            api,
            object : InstallId { override val value = "client-1" },
            markSignedOut = { signedOut += it.id },
            nowMs = { now },
        )
        val progressDao = InMemoryProgressDao(local)
        val pendingDao = InMemoryPendingProgressDao()
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(book, absCopy)), progressDao, InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(listOf(server)), listOf(source),
            pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(),
            clock = { now },
        )
        return Fixture(repo, jf, progressDao, pendingDao, signedOut, source)
    }

    // ---- Mark as finished / not finished ----

    @Test
    fun `marking a Jellyfin book finished marks it played on the server`() = runTest {
        val f = fixture(listOf(ProgressEntity(bookId, 1_500_000L, hour, 100L, false)))

        assertEquals(MarkOutcome.SYNCED, f.repo.markFinished(bookId, true).getOrThrow())

        assertEquals(listOf("POST /UserPlayedItems/$itemId"), f.jf.writes())
        assertTrue(f.jf.played)
        assertTrue(f.progressDao.get(bookId)!!.isFinished)
        assertTrue(f.pendingDao.rows.value.isEmpty())
    }

    @Test
    fun `marking a Jellyfin book not finished clears the played flag and starts over`() = runTest {
        val f = fixture(listOf(ProgressEntity(bookId, hour, hour, 100L, true)))
        f.jf.played = true
        f.jf.lastPlayed = jellyfinIsoTime(50L)

        assertEquals(MarkOutcome.SYNCED, f.repo.markFinished(bookId, false).getOrThrow())

        assertEquals(listOf("POST /UserItems/$itemId/UserData"), f.jf.writes())
        assertFalse(f.jf.played)
        assertEquals(0L, f.jf.positionTicks)
        val row = f.progressDao.get(bookId)!!
        assertEquals(0L, row.positionMs)
        assertFalse(row.isFinished)
    }

    @Test
    fun `an unreachable Jellyfin server queues the mark`() = runTest {
        val f = fixture()
        f.jf.down = true

        assertEquals(MarkOutcome.QUEUED, f.repo.markFinished(bookId, true).getOrThrow())
        assertEquals(true, f.pendingDao.rows.value.single().markedFinished)
    }

    // ---- Queued positions and mirrors through the repository ----

    @Test
    fun `a queued position is one UserData write dated with the row's time`() = runTest {
        val f = fixture(now = 1_900_000_000_000L)
        val rowAt = 1_793_000_000_000L

        f.repo.pushProgress(PendingProgress(bookId, "jf1", 1_234_000L, hour, 0L, rowAt)).getOrThrow()

        assertEquals(listOf("POST /UserItems/$itemId/UserData"), f.jf.writes())
        assertEquals(1_234_000L * 10_000L, f.jf.positionTicks)
        assertEquals(rowAt, jellyfinTimeMs(f.jf.lastPlayed))
        assertTrue(f.jf.requests.none { it.url.encodedPath.contains("/Sessions") })
    }

    @Test
    fun `a mirror is dated with the source copy's listening when that is older`() = runTest {
        val sourceAt = 1_792_999_990_000L
        val f = fixture(listOf(ProgressEntity(absCopy.id, 1_000_000L, hour, sourceAt, false)))

        f.repo.pushProgress(
            PendingProgress(bookId, "jf1", 1_000_000L, hour, 0L, updatedAtMs = 1_793_000_000_000L, mirrorOf = absCopy.id)
        ).getOrThrow()

        assertEquals(sourceAt, jellyfinTimeMs(f.jf.lastPlayed))
        // The next reading of the server is no newer than the source copy, so the mirrored row stays.
        assertEquals(sourceAt, f.repo.fetchServerProgress(bookId).getOrThrow()!!.updatedAtMs)
    }

    @Test
    fun `a mirror queued before the source's last listening keeps its own time`() = runTest {
        val f = fixture(listOf(ProgressEntity(absCopy.id, 1_000_000L, hour, 1_793_000_005_000L, false)))
        f.repo.pushProgress(
            PendingProgress(bookId, "jf1", 1_000_000L, hour, 0L, updatedAtMs = 1_793_000_000_000L, mirrorOf = absCopy.id)
        ).getOrThrow()
        assertEquals(1_793_000_000_000L, jellyfinTimeMs(f.jf.lastPlayed))
    }

    // ---- The flusher, including rows J1 queued (it refused every report as "not yet") ----

    /** Runs a flusher over [f] on a real dispatcher (the mock engine answers on its own threads) until [done] holds. */
    private fun flushUntil(f: Fixture, now: Long, done: () -> Boolean) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            PendingProgressFlusher(f.repo, FakeConnectivity(true), scope, { now }).start()
            withTimeout(5_000L) { while (!done()) delay(10L) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun InMemoryPendingProgressDao.queue(row: PendingProgress) =
        upsert(PendingProgressEntity.fromDomain(row, row.nextAttemptAtMs))

    /** A row J1 queued: it failed many times with "not yet", so it carries attempts and a retry time already past. */
    private fun j1Row(at: Long, positionMs: Long = 1_800_000L, markedFinished: Boolean? = null) =
        PendingProgress(bookId, "jf1", positionMs, hour, 10_000L, at, attempts = 9, nextAttemptAtMs = at + 300_000L, markedFinished = markedFinished)

    @Test
    fun `queue delivers only UserData`() {
        val now = 1_793_000_900_000L
        val f = fixture(now = now)
        val rowAt = 1_793_000_000_000L
        runBlocking { f.pendingDao.queue(j1Row(rowAt)) }

        flushUntil(f, now) { f.pendingDao.rows.value.isEmpty() }

        assertEquals(listOf("POST /UserItems/$itemId/UserData"), f.jf.writes())
        assertEquals(1_800_000L * 10_000L, f.jf.positionTicks)
        assertEquals(rowAt, jellyfinTimeMs(f.jf.lastPlayed))
        assertTrue(f.jf.lastBody().contains("\"Played\":false"))
    }

    @Test
    fun `a J1 row is dropped when the server holds newer listening`() {
        val now = 1_793_000_900_000L
        val f = fixture(now = now)
        f.jf.positionTicks = 2_400_000L * 10_000L
        f.jf.lastPlayed = jellyfinIsoTime(1_793_000_500_000L)
        runBlocking { f.pendingDao.queue(j1Row(1_793_000_000_000L)) }

        flushUntil(f, now) { f.pendingDao.rows.value.isEmpty() }

        assertTrue(f.jf.writes().isEmpty())
        // The server's place is stored here instead.
        assertEquals(2_400_000L, f.progressDao.rows.value.single { it.bookId == bookId }.positionMs)
    }

    @Test
    fun `a J1 queued finished mark is delivered as played`() {
        val now = 1_793_000_900_000L
        val f = fixture(now = now)
        runBlocking { f.pendingDao.queue(j1Row(1_793_000_000_000L, positionMs = hour, markedFinished = true)) }

        flushUntil(f, now) { f.pendingDao.rows.value.isEmpty() }

        assertEquals(listOf("POST /UserPlayedItems/$itemId"), f.jf.writes())
        assertTrue(f.jf.played)
    }

    @Test
    fun `a refused token stops the flusher with Sign in again and keeps the row`() {
        val now = 1_793_000_900_000L
        val f = fixture(now = now)
        f.jf.status = HttpStatusCode.Unauthorized
        runBlocking { f.pendingDao.queue(j1Row(1_793_000_000_000L)) }

        flushUntil(f, now) { f.signedOut.isNotEmpty() }

        assertEquals(listOf("jf1"), f.signedOut.distinct())
        assertTrue(f.jf.writes().isEmpty())
        val kept = f.pendingDao.rows.value.single()
        // Not counted as a failed attempt: it waits for the sign-in.
        assertEquals(9, kept.attempts)
        assertIs<SignInRequiredException>(runBlocking { f.repo.fetchServerProgress(bookId) }.exceptionOrNull())
    }

    @Test
    fun `a J1 row older than the queue's limit is dropped unsent`() {
        val rowAt = 1_793_000_000_000L
        val now = rowAt + 31L * 24 * 3_600_000L
        val f = fixture(now = now)
        runBlocking { f.pendingDao.queue(j1Row(rowAt)) }

        flushUntil(f, now) { f.pendingDao.rows.value.isEmpty() }

        assertTrue(f.jf.requests.isEmpty())
        assertTrue(f.progressDao.rows.value.none { it.bookId == bookId })
    }

    // ---- the close (review fix): a place the server may not have is queued ----

    @Test
    fun `a failed write after the stop queues the place and the flusher writes it exactly`() {
        val now = 1_793_000_900_000L
        val f = fixture(now = now)
        val place = hour - 3 * 60_000L // in the end band: the stop alone would lose it
        runBlocking {
            val session = f.source.startPlayback(server, bookId)
            assertTrue(f.repo.syncPlayback(session, 30 * 60_000L, 10_000L, isPlaying = true).isSuccess)
            f.jf.failWrites = true
            val closed = f.repo.closePlayback(session, place, 5_000L)
            assertIs<com.chapterplayer.data.source.PlaceNotSavedException>(closed.exceptionOrNull())
            val row = f.pendingDao.rows.value.single()
            assertEquals(bookId, row.bookId)
            assertEquals(place, row.positionMs)
            assertEquals(now, row.updatedAtMs)
        }
        f.jf.failWrites = false
        val writesBefore = f.jf.writes().size
        flushUntil(f, now) { f.pendingDao.rows.value.isEmpty() }
        assertEquals(listOf("POST /UserItems/$itemId/UserData"), f.jf.writes().drop(writesBefore))
        assertEquals(place * 10_000L, f.jf.positionTicks)
        assertFalse(f.jf.played)
    }

    @Test
    fun `a close that reached the server queues nothing`() = runTest {
        val f = fixture()
        val session = f.source.startPlayback(server, bookId)
        f.repo.syncPlayback(session, 30 * 60_000L, 10_000L, isPlaying = true)
        assertTrue(f.repo.closePlayback(session, hour - 3 * 60_000L, 5_000L).isSuccess)
        assertTrue(f.pendingDao.rows.value.isEmpty())
    }
}
