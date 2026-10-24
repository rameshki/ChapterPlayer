package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.playback.SIGN_IN_AGAIN_TEXT
import com.chapterplayer.data.repository.InMemoryDownloadDao
import com.chapterplayer.domain.downloads.DownloadStatus
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookDownloaderTest {

    private val root: File = Files.createTempDirectory("bookdl").toFile()
    private val bookId = "abs_li_1"
    private val dir = File(root, "downloads/srv1/abs_li_1")
    private val first = content(3_000)
    private val second = content(5_000).reversedArray()
    private val cover = byteArrayOf(1, 2, 3, 4)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val itemJson = """
        {"id":"li_1","media":{
          "chapters":[{"id":0,"start":0,"end":10,"title":"One"},{"id":1,"start":10,"end":20,"title":"Two"}],
          "audioFiles":[
            {"index":2,"ino":"12","metadata":{"filename":"Two.mp3","size":5000}},
            {"index":1,"ino":"11","metadata":{"filename":"One.mp3","size":3000}}
          ]}}
    """.trimIndent()

    private fun book(chapters: List<Chapter> = emptyList(), coverUrl: String? = "http://abs.test/api/items/li_1/cover") = BookEntity(
        id = bookId, title = "Fixture Book", author = "A", narrator = "N", durationMs = 20_000,
        chaptersJson = ChapterJson.encode(chapters), streamUrl = "", coverUrl = coverUrl,
        source = "ABS", serverId = "srv1", cachedAt = 0
    )

    private fun queued() = DownloadEntity(bookId, "srv1", DownloadStatus.QUEUED.name, 0, null, 0)

    /** Serves the item, the cover and both files; [file] can change the answer for a file request. */
    private fun fakeServer(
        file: (suspend MockRequestHandleScope.(HttpRequestData, ByteArray) -> HttpResponseData)? = null,
    ) = FakeAbs { req ->
        val path = req.url.encodedPath
        when {
            path == "/api/items/li_1" -> respond(itemJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            path == "/api/items/li_1/cover" -> respond(cover, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            path.endsWith("/file/11/download") -> file?.invoke(this, req, first) ?: serve(first, req)
            path.endsWith("/file/12/download") -> file?.invoke(this, req, second) ?: serve(second, req)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private class Rig(
        val dao: InMemoryDownloadDao,
        val books: FakeBookDao,
        val downloader: BookDownloader,
        val sleeps: MutableList<Long>,
    )

    private fun rig(
        fake: FakeAbs,
        dao: InMemoryDownloadDao = InMemoryDownloadDao(listOf(queued())),
        books: FakeBookDao = FakeBookDao(book()),
        freeBytes: Long = 10L * 1024 * 1024 * 1024,
        limitBytes: Long? = null,
        maxAttempts: Int = Int.MAX_VALUE,
        stallMs: Long = 60_000,
    ): Rig {
        val servers = FakeServers(testServer())
        val authorizer = fake.authorizer(servers)
        val sleeps = CopyOnWriteArrayList<Long>()
        val location = object : DownloadLocation {
            override fun rootDir(): File = root
            override fun freeBytes(): Long = freeBytes
        }
        val downloader = BookDownloader(
            dao = dao, bookDao = books, servers = servers, api = fake.api, authorizer = authorizer,
            files = FileDownloader(fake.downloadClient, authorizer, stallMs = stallMs),
            location = location, limitBytes = { limitBytes }, sleep = { sleeps += it }, maxAttempts = maxAttempts,
        )
        return Rig(dao, books, downloader, sleeps)
    }

    private fun Rig.status() = dao.downloads.value.single().status

    @Test
    fun `a first run plans the files in order, downloads them, and ends DONE`() = runBlocking {
        val fake = fakeServer()
        val rig = rig(fake)

        val result = rig.downloader.run(bookId)

        assertEquals(BookResult.Done, result)
        assertEquals(DownloadStatus.DONE.name, rig.status())
        assertEquals(8_000L, rig.dao.downloads.value.single().totalBytes)
        val rows = rig.dao.files.value.sortedBy { it.sortOrder }
        assertEquals(listOf("11", "12"), rows.map { it.ino })
        assertEquals(listOf(3_000L, 5_000L), rows.map { it.doneBytes })
        assertEquals(listOf(File(dir, "11.mp3").path, File(dir, "12.mp3").path), rows.map { it.path })
        assertContentEquals(first, File(dir, "11.mp3").readBytes())
        assertContentEquals(second, File(dir, "12.mp3").readBytes())
        assertEquals(
            listOf("/api/items/li_1", "/api/items/li_1/file/11/download", "/api/items/li_1/file/12/download"),
            fake.requests.map { it.url.encodedPath }.filter { it != "/api/items/li_1/cover" }
        )
        assertEquals(1f, rig.downloader.progress(bookId).first()?.fraction)
    }

    @Test
    fun `chapters missing from the book row are saved, and the cover is stored next to the files`() = runBlocking {
        val rig = rig(fakeServer())

        rig.downloader.run(bookId)

        assertEquals(listOf(Chapter(0, "One", 0, 10_000), Chapter(1, "Two", 10_000, 20_000)), rig.books.chapterUpdates.single())
        assertContentEquals(cover, File(dir, "cover.jpg").readBytes())
    }

    @Test
    fun `chapters already in the book row and a stored cover are not fetched again`() = runBlocking {
        dir.mkdirs()
        File(dir, "cover.jpg").writeBytes(byteArrayOf(9))
        val fake = fakeServer()
        val rig = rig(fake, books = FakeBookDao(book(chapters = listOf(Chapter(0, "Kept", 0, 20_000)))))

        rig.downloader.run(bookId)

        assertTrue(rig.books.chapterUpdates.isEmpty())
        assertTrue(fake.requests.none { it.url.encodedPath.endsWith("/cover") })
        assertContentEquals(byteArrayOf(9), File(dir, "cover.jpg").readBytes())
    }

    @Test
    fun `a multi-file book resumes at the first unfinished file and its recorded byte`() = runBlocking {
        dir.mkdirs()
        File(dir, "11.mp3").writeBytes(first)
        File(dir, "12.mp3.part").writeBytes(second.copyOfRange(0, 1_200))
        val dao = InMemoryDownloadDao(
            listOf(queued().copy(totalBytes = 8_000)),
            listOf(
                DownloadFileEntity(bookId, "11", "One.mp3", File(dir, "11.mp3").path, 3_000, 3_000, 0),
                DownloadFileEntity(bookId, "12", "Two.mp3", File(dir, "12.mp3").path, 5_000, 1_000, 1),
            )
        )
        val fake = fakeServer()
        val rig = rig(fake, dao = dao, books = FakeBookDao(book(coverUrl = null)))

        assertEquals(BookResult.Done, rig.downloader.run(bookId))

        val sent = fake.requests.single()
        assertEquals("/api/items/li_1/file/12/download", sent.url.encodedPath)
        assertEquals("bytes=1000-", sent.headers[HttpHeaders.Range])
        assertContentEquals(second, File(dir, "12.mp3").readBytes())
        assertEquals(DownloadStatus.DONE.name, rig.status())
    }

    @Test
    fun `not enough space fails before any file is requested`() = runBlocking {
        val fake = fakeServer()
        val rig = rig(fake, freeBytes = 50L * 1024 * 1024)

        val result = rig.downloader.run(bookId)

        assertEquals(BookResult.Failed("Not enough space"), result)
        assertEquals(DownloadStatus.FAILED.name, rig.status())
        assertEquals("Not enough space", rig.dao.downloads.value.single().error)
        assertTrue(fake.requests.none { it.url.encodedPath.endsWith("/download") })
    }

    @Test
    fun `a storage limit that would be passed also fails with not enough space`() = runBlocking {
        val other = DownloadFileEntity("abs_other", "1", "x.mp3", "/x", 9_000, 9_000, 0)
        val dao = InMemoryDownloadDao(listOf(queued()), listOf(other))
        val rig = rig(fakeServer(), dao = dao, limitBytes = 10_000)

        assertEquals(BookResult.Failed("Not enough space"), rig.downloader.run(bookId))
    }

    @Test
    fun `a refused sign-in fails with sign in again and no retry loop`() = runBlocking {
        val fake = fakeServer(file = { _, _ -> respond("", HttpStatusCode.Unauthorized) })
        val rig = rig(fake)

        val result = rig.downloader.run(bookId)

        assertEquals(BookResult.Failed(SIGN_IN_AGAIN_TEXT), result)
        assertEquals(DownloadStatus.FAILED.name, rig.status())
        assertTrue(rig.sleeps.isEmpty())
    }

    @Test
    fun `a file the server no longer has fails the book`() = runBlocking {
        val fake = fakeServer(file = { _, _ -> respond("", HttpStatusCode.NotFound) })
        val rig = rig(fake)

        val result = rig.downloader.run(bookId)

        assertTrue(result is BookResult.Failed)
        assertEquals(DownloadStatus.FAILED.name, rig.status())
    }

    @Test
    fun `a book with no downloadable files fails`() = runBlocking {
        val fake = FakeAbs { respond("""{"id":"li_1","media":{"audioFiles":[]}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val rig = rig(fake)

        assertTrue(rig.downloader.run(bookId) is BookResult.Failed)
        assertEquals(DownloadStatus.FAILED.name, rig.status())
    }

    @Test
    fun `retries back off and the attempt count starts again after progress`() = runBlocking {
        val answers = ArrayDeque(listOf("cut", "busy", "cut", "full"))
        val fake = fakeServer(file = { req, body ->
            if (body.size != first.size) serve(body, req)
            else when (answers.removeFirst()) {
                "cut" -> serve(body, req, cutAt = ((req.rangeStart() ?: 0L) + 500).toInt())
                "busy" -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> serve(body, req)
            }
        })
        val rig = rig(fake)

        assertEquals(BookResult.Done, rig.downloader.run(bookId))

        assertEquals(listOf(5_000L, 10_000L, 5_000L), rig.sleeps)
        assertContentEquals(first, File(dir, "11.mp3").readBytes())
    }

    @Test
    fun `a server that keeps failing gives up after the allowed attempts and stays queued`() = runBlocking {
        val fake = fakeServer(file = { _, _ -> respond("", HttpStatusCode.BadGateway) })
        val rig = rig(fake, maxAttempts = 3)

        assertEquals(BookResult.Retry, rig.downloader.run(bookId))
        assertEquals(listOf(5_000L, 10_000L), rig.sleeps)
        assertEquals(DownloadStatus.QUEUED.name, rig.status())
    }

    @Test
    fun `a missing download row does nothing`() = runBlocking {
        val fake = fakeServer()
        val rig = rig(fake, dao = InMemoryDownloadDao())

        assertEquals(BookResult.Missing, rig.downloader.run(bookId))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `a paused download is left alone`() = runBlocking {
        val fake = fakeServer()
        val rig = rig(fake, dao = InMemoryDownloadDao(listOf(queued().copy(status = DownloadStatus.PAUSED.name))))

        assertEquals(BookResult.Paused, rig.downloader.run(bookId))
        assertEquals(DownloadStatus.PAUSED.name, rig.status())
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `stopping the job saves the bytes so far and leaves the book queued`() = runBlocking {
        val fake = fakeServer(file = { _, body ->
            val stalled = CoroutineScope(Dispatchers.IO).writer {
                channel.writeFully(body.copyOfRange(0, 700))
                channel.flush()
                awaitCancellation()
            }.channel
            respond(stalled, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        })
        val rig = rig(fake)

        val job = async(Dispatchers.IO) { rig.downloader.run(bookId) }
        withTimeout(10_000) {
            while (fake.requests.none { it.url.encodedPath.endsWith("/download") }) delay(20)
            delay(500) // the first bytes are read (they sit in the write buffer until the file closes)
        }
        job.cancelAndJoin()

        assertEquals(DownloadStatus.QUEUED.name, rig.status())
        assertEquals(700L, rig.dao.files.value.first { it.ino == "11" }.doneBytes)
    }
}

/** Only what the downloader uses; the rest is never called. */
internal class FakeBookDao(initial: BookEntity?) : BookDao {
    private val rows = MutableStateFlow(listOfNotNull(initial))
    val chapterUpdates = CopyOnWriteArrayList<List<Chapter>>()

    override fun observeAll(): Flow<List<BookEntity>> = rows
    override fun observeBySource(source: String): Flow<List<BookEntity>> = rows
    override fun observeByServer(serverId: String): Flow<List<BookEntity>> = rows
    override suspend fun getById(id: String): BookEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun getByServer(serverId: String): List<BookEntity> = rows.value.filter { it.serverId == serverId }
    override suspend fun insertAll(books: List<BookEntity>) { rows.value = books }
    override suspend fun insert(book: BookEntity) { rows.value = rows.value.filterNot { it.id == book.id } + book }
    override suspend fun updateChapters(id: String, json: String) {
        chapterUpdates += ChapterJson.decode(json)
        rows.value = rows.value.map { if (it.id == id) it.copy(chaptersJson = json) else it }
    }
    override suspend fun deleteByServer(serverId: String) {}
    override suspend fun deleteByLibrary(serverId: String, libraryId: String) {}
    override suspend fun deleteAll() {}
    override suspend fun getLastCacheTime(serverId: String): Long? = null
}
