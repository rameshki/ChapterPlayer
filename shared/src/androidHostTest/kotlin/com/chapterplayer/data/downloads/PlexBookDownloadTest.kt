package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.SIGN_IN_AGAIN_TEXT
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.installDownloadDefaults
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.repository.InMemoryDownloadDao
import com.chapterplayer.data.source.PlexLibrarySource
import com.chapterplayer.domain.downloads.DownloadStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Plex book through the whole downloader: plan, files, sign-in and a connection that changes. Synthetic data only. */
class PlexBookDownloadTest {

    private val root: File = Files.createTempDirectory("plexdl").toFile()
    private val bookId = "plex_1101"
    private val dir = File(root, "downloads/plex1/plex_1101")

    private val server = ServerConfig(
        id = "plex1", source = ServerSource.PLEX, name = "Test Plex", baseUrl = "http://plex.test:32400", token = "tok", libraryId = null
    )

    /** The recorded three-track album with small synthetic sizes, so the test files stay small. */
    private val tracks = fixture("plex/album-tracks-multi.json")
        // The length the third track lacks in the recording: a book with an unknown length is refused.
        .replace("\"ratingKey\": \"1104\",", "\"ratingKey\": \"1104\", \"duration\": 300000,")
        .replace("\"size\": 9600000,", "\"size\": 3000,")
        .replace("\"size\": 14400000,", "\"size\": 5000,")
        .replace("\"size\": 1000000,", "\"size\": 2000,")
    private val noChapters = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1102","type":"track","title":"Fixture title 13"}]}}"""

    private val bodies = mapOf(
        "/library/parts/1202/1700000000/file.mp3" to content(3_000),
        "/library/parts/1203/1700000000/file.mp3" to content(5_000).reversedArray(),
        "/library/parts/1204/1700000000/file.mp3" to ByteArray(2_000) { (it % 7).toByte() },
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val partRequests get() = requests.filter { it.url.encodedPath.startsWith("/library/parts/") }

    private fun book() = BookEntity(
        id = bookId, title = "Fixture Book", author = "A", narrator = "", durationMs = 0,
        chaptersJson = "[]", streamUrl = "", coverUrl = null, source = "PLEX", serverId = "plex1", cachedAt = 0
    )

    private inner class Rig(
        val dao: InMemoryDownloadDao = InMemoryDownloadDao(listOf(DownloadEntity(bookId, "plex1", DownloadStatus.QUEUED.name, 0, null, 0))),
        onConnectionFailure: suspend (ServerConfig, Throwable) -> ServerConfig? = { _, _ -> null },
        prepare: suspend (ServerConfig) -> ServerConfig? = { it },
        val children: String = tracks,
        val part: suspend MockRequestHandleScope.(HttpRequestData, ByteArray) -> HttpResponseData = { req, body -> serve(body, req) },
    ) {
        val servers = FakeServers(server)
        val books = FakeBookDao(book())
        private val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path.startsWith("/library/parts/") -> bodies[path]?.let { part(request, it) } ?: respond("", HttpStatusCode.NotFound)
                path.endsWith("/children") -> respond(children, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(noChapters, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        private val downloadClient = HttpClient(engine) { installDownloadDefaults(isDebuggable = false, sink = {}) }
        val plex = PlexLibrarySource(
            PlexApiService(
                HttpClient(engine) { installApiDefaults(isDebuggable = false, sink = {}) },
                object : PlexClientIdentifier { override val value = "client-1" },
            ),
            // As the app does: a refused token is blanked.
            markSignedOut = { refused -> servers.getById(refused.id)?.let { servers.save(it.copy(token = "")) } },
        )
        val downloader = BookDownloader(
            dao = dao, bookDao = books, servers = servers,
            sources = listOf(PlexDownloadSource(plex, servers, downloadClient, onConnectionFailure, prepare)),
            files = FileDownloader(downloadClient),
            location = object : DownloadLocation {
                override suspend fun rootDir(): File = root
                override suspend fun freeBytes(): Long = 10L * 1024 * 1024 * 1024
            },
            sleep = {}, maxAttempts = 2,
        )

        fun status() = dao.downloads.value.single().status
    }

    @Test
    fun `a Plex book downloads track after track into its folder with the token header`() = runBlocking {
        val rig = Rig()

        assertEquals(BookResult.Done, rig.downloader.run(bookId))

        assertEquals(DownloadStatus.DONE.name, rig.status())
        assertEquals(10_000L, rig.dao.downloads.value.single().totalBytes)
        val rows = rig.dao.files.value.sortedBy { it.sortOrder }
        assertEquals(listOf("1102", "1103", "1104"), rows.map { it.ino })
        assertEquals(listOf(File(dir, "1102.mp3").path, File(dir, "1103.mp3").path, File(dir, "1104.mp3").path), rows.map { it.path })
        assertEquals(listOf(600_000L, 900_000L, 300_000L), rows.map { it.durationMs })
        assertEquals(listOf("audio/mpeg", "audio/mpeg", "audio/mpeg"), rows.map { it.mimeType })
        for ((i, path) in bodies.keys.withIndex()) assertContentEquals(bodies.getValue(path), File(rows[i].path).readBytes())
        assertEquals(bodies.keys.toList(), partRequests.map { it.url.encodedPath })
        assertTrue(partRequests.all { it.headers["X-Plex-Token"] == "tok" && it.headers[HttpHeaders.Authorization] == null })
        assertTrue(requests.none { "tok" in it.url.toString() })
        // The chapters the book row lacked are kept for playing offline.
        assertEquals(3, ChapterJson.decode(rig.books.getById(bookId)!!.chaptersJson).size)
    }

    @Test
    fun `a refused token stops the download with Sign in again and blanks the token`() = runBlocking {
        val rig = Rig(part = { _, _ -> respond("", HttpStatusCode.Unauthorized) })

        val result = rig.downloader.run(bookId)

        assertEquals(BookResult.Failed(SIGN_IN_AGAIN_TEXT), result)
        assertEquals(DownloadStatus.FAILED.name, rig.status())
        assertEquals("", rig.servers.holder.value.token)
    }

    @Test
    fun `a connection lost mid-file re-probes and resumes the same part file at the new address from the right byte`() = runBlocking {
        val moved = server.copy(baseUrl = "http://10.0.0.2:32400")
        val probes = CopyOnWriteArrayList<String>()
        lateinit var rig: Rig
        rig = Rig(
            onConnectionFailure = { used, _ ->
                probes += used.baseUrl
                // The connection manager saves the new address and hands it back.
                rig.servers.save(moved)
                moved
            },
            part = { req, body ->
                if (req.url.host == "plex.test" && req.url.encodedPath.contains("/1203/")) {
                    // Home Wi-Fi drops after 1 200 bytes of the second file.
                    val cut = CoroutineScope(Dispatchers.IO).writer {
                        channel.writeFully(body.copyOfRange(0, 1_200))
                        channel.flush()
                        delay(300) // let the reader take the bytes before the connection breaks
                        throw IOException("connection reset")
                    }.channel
                    respond(cut, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
                } else {
                    serve(body, req)
                }
            },
        )

        assertEquals(BookResult.Done, rig.downloader.run(bookId))

        assertEquals(listOf("http://plex.test:32400"), probes)
        val second = partRequests.filter { it.url.encodedPath.contains("/1203/") }
        assertEquals(listOf("plex.test", "10.0.0.2"), second.map { it.url.host })
        assertEquals("bytes=1200-", second.last().headers[HttpHeaders.Range])
        assertEquals("tok", second.last().headers["X-Plex-Token"])
        assertContentEquals(bodies.getValue("/library/parts/1203/1700000000/file.mp3"), File(dir, "1103.mp3").readBytes())
        // Only the cut request went to the old address; every later one, the third file too, to the new one.
        val afterCut = partRequests.dropWhile { !(it.url.host == "plex.test" && it.url.encodedPath.contains("/1203/")) }.drop(1)
        assertEquals(2, afterCut.size)
        assertTrue(afterCut.all { it.url.host == "10.0.0.2" })
        assertEquals(listOf("/1203/", "/1204/"), afterCut.map { if ("/1203/" in it.url.encodedPath) "/1203/" else "/1204/" })
        assertEquals(DownloadStatus.DONE.name, rig.status())
    }

    @Test
    fun `a job whose address is plain http and not verified here sends nothing and waits`() = runBlocking {
        val rig = Rig(prepare = { null })

        val result = rig.downloader.run(bookId)

        assertEquals(BookResult.Retry, result)
        assertEquals(DownloadStatus.QUEUED.name, rig.status())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an album with a track of unknown length fails at once with the reason and downloads nothing`() = runBlocking {
        val rig = Rig(children = tracks.replace("\"ratingKey\": \"1104\", \"duration\": 300000,", "\"ratingKey\": \"1104\","))

        val result = rig.downloader.run(bookId)

        assertEquals(BookResult.Failed(com.chapterplayer.domain.plex.PLEX_LENGTH_UNKNOWN_TEXT), result)
        assertTrue(partRequests.isEmpty())
        assertTrue(rig.dao.files.value.isEmpty())
    }

    @Test
    fun `without a new address a lost connection is retried later from the saved byte`() = runBlocking {
        var dropped = false
        val rig = Rig(part = { req, body ->
            if (!dropped && req.url.encodedPath.contains("/1202/")) {
                dropped = true
                throw IOException("unreachable")
            }
            serve(body, req)
        })

        assertEquals(BookResult.Done, rig.downloader.run(bookId))
        assertEquals(DownloadStatus.DONE.name, rig.status())
    }
}
