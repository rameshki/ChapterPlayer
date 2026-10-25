package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.source.PlexLibrarySource
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.testing.FakeServerList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Mark as finished / not finished" and queued positions for a Plex book, through the real Plex source on a tiny mock server. */
class BookRepositoryPlexMarkTest {

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

    /**
     * Three tracks of 1000 s each. A scrobble counts a play and clears the offset, an unscrobble clears both, a timeline sets
     * the offset; the track list reports that state, with `lastViewedAt` from [clockSec].
     */
    private class MockPlex {
        val requests = mutableListOf<HttpRequestData>()
        var down = false
        var clockSec = 1_700_000_000L
        val keys = listOf("11", "12", "13")
        val viewCount = mutableMapOf("11" to 0, "12" to 0, "13" to 0)
        val offset = mutableMapOf<String, Long>()
        val viewedAt = mutableMapOf<String, Long>()

        private fun tracksJson() = keys.joinToString(",", """{"MediaContainer":{"size":3,"Metadata":[""", "]}}") { key ->
            val state = buildString {
                viewCount.getValue(key).takeIf { it > 0 }?.let { append(""","viewCount":$it""") }
                offset[key]?.let { append(""","viewOffset":$it""") }
                viewedAt[key]?.let { append(""","lastViewedAt":$it""") }
            }
            """{"ratingKey":"$key","type":"track","title":"T$key","duration":1000000$state,
               "Media":[{"Part":[{"id":$key,"key":"/library/parts/$key/1/file.mp3","duration":1000000}]}]}"""
        }

        val engine = MockEngine { request ->
            requests += request
            if (down) throw IOException("offline")
            val path = request.url.encodedPath
            val key = request.url.parameters["key"]
            when {
                path == "/:/scrobble" -> {
                    viewCount[key!!] = viewCount.getValue(key) + 1
                    offset.remove(key)
                    viewedAt[key] = clockSec
                    respond("", HttpStatusCode.OK)
                }
                path == "/:/unscrobble" -> {
                    viewCount[key!!] = 0
                    offset.remove(key)
                    respond("", HttpStatusCode.OK)
                }
                path == "/:/timeline" -> {
                    val rating = request.url.parameters["ratingKey"]!!
                    val time = request.url.parameters["time"]!!.toLong()
                    if (time > 0) offset[rating] = time else offset.remove(rating)
                    viewedAt[rating] = clockSec
                    respond("", HttpStatusCode.OK)
                }
                path.endsWith("/children") ->
                    respond(tracksJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }

        fun reports() = requests.filter { it.url.encodedPath.startsWith("/:/") }
            .map { it.url.encodedPath.removePrefix("/:/") + " " + (it.url.parameters["key"]?.removePrefix("/library/metadata/") ?: "") }
    }

    private val server = ServerConfig("srv1", ServerSource.PLEX, "P", "http://plex.test:32400", "tok-secret")
    private val book = BookEntity("plex_10", "T", "A", "", 3_000_000L, "[]", "u", null, "PLEX", "srv1", 0L)

    private class Fixture(val repo: BookRepositoryImpl, val plex: MockPlex, val progressDao: InMemoryProgressDao, val pendingDao: InMemoryPendingProgressDao)

    private fun fixture(local: List<ProgressEntity> = emptyList()): Fixture {
        val plex = MockPlex()
        val api = PlexApiService(HttpClient(plex.engine) { installApiDefaults(isDebuggable = false) })
        val progressDao = InMemoryProgressDao(local)
        val pendingDao = InMemoryPendingProgressDao()
        val repo = BookRepositoryImpl(
            FakeBookDao(listOf(book)), progressDao, InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(listOf(server)), listOf(PlexLibrarySource(api)),
            pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(),
            clock = { 1_000L },
        )
        return Fixture(repo, plex, progressDao, pendingDao)
    }

    @Test
    fun `marking a Plex book finished scrobbles every track`() = runTest {
        val f = fixture(listOf(ProgressEntity("plex_10", 1_500_000L, 3_000_000L, 100L, false)))

        assertEquals(MarkOutcome.SYNCED, f.repo.markFinished("plex_10", true).getOrThrow())

        assertEquals(listOf("scrobble 11", "scrobble 12", "scrobble 13"), f.plex.reports())
        assertTrue(f.progressDao.get("plex_10")!!.isFinished)
        assertTrue(f.plex.requests.all { it.headers["X-Plex-Token"] == "tok-secret" && it.url.parameters["X-Plex-Token"] == null })
        assertTrue(f.plex.requests.filter { it.url.encodedPath.startsWith("/:/") }.all { it.url.parameters["identifier"] == "com.plexapp.plugins.library" })
    }

    @Test
    fun `marking a Plex book not finished unscrobbles the played tracks and starts over`() = runTest {
        val f = fixture(listOf(ProgressEntity("plex_10", 3_000_000L, 3_000_000L, 100L, true)))
        f.plex.keys.forEach { f.plex.viewCount[it] = 1 }

        assertEquals(MarkOutcome.SYNCED, f.repo.markFinished("plex_10", false).getOrThrow())

        assertEquals(listOf("unscrobble 11", "unscrobble 12", "unscrobble 13", "timeline 11"), f.plex.reports())
        val row = f.progressDao.get("plex_10")!!
        assertEquals(0L, row.positionMs)
        assertEquals(false, row.isFinished)
    }

    @Test
    fun `an unreachable Plex server queues the mark`() = runTest {
        val f = fixture()
        f.plex.down = true

        assertEquals(MarkOutcome.QUEUED, f.repo.markFinished("plex_10", true).getOrThrow())
        assertEquals(true, f.pendingDao.rows.value.single().markedFinished)
    }

    @Test
    fun `a queued position finishes the tracks before it and sets the offset in its own`() = runTest {
        val f = fixture()

        f.repo.pushProgress(PendingProgress("plex_10", "srv1", 1_250_000L, 3_000_000L, 0L, 1_000L)).getOrThrow()

        assertEquals(listOf("timeline 12", "scrobble 11"), f.plex.reports())
        val timeline = f.plex.requests.first { it.url.encodedPath == "/:/timeline" }
        assertEquals("stopped", timeline.url.parameters["state"])
        assertEquals("250000", timeline.url.parameters["time"])
        assertEquals("1000000", timeline.url.parameters["duration"])
        // The server now says the book is where the queue said.
        assertEquals(1_250_000L, f.repo.fetchServerProgress("plex_10").getOrThrow()!!.positionMs)
    }
}
