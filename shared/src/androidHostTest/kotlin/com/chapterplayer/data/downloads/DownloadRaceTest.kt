package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.repository.InMemoryDownloadDao
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.FakeAppSettings
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The controller and a real [BookDownloader] together: a stopping job never races the next owner. */
class DownloadRaceTest {

    private val root: File = Files.createTempDirectory("dlrace").toFile()
    private val bookId = "abs_li_1"
    private val dir = File(root, "downloads/srv1/abs_li_1")
    private val body = content(5_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    /** Like WorkManager: cancel does not wait, and a cancelled job does not block scheduling a new one. */
    private inner class RunningScheduler(val downloader: BookDownloader, val controller: () -> DefaultDownloadController) : DownloadScheduler {
        val jobs = ConcurrentHashMap<String, Job>()
        override suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean) {
            val existing = jobs[bookId]
            if (!replace && existing?.isActive == true) return
            if (replace) existing?.cancel()
            jobs[bookId] = scope.launch {
                try {
                    downloader.run(bookId)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { controller().afterStop(bookId, timedOut = false) }
                    throw e
                }
            }
        }
        override suspend fun cancel(bookId: String) {
            jobs[bookId]?.cancel()
        }
    }

    private inner class Rig {
        val calls = AtomicInteger()
        /** The first file request sends 700 bytes and then nothing; later ones are answered in full. */
        val fake = FakeAbs { req ->
            val path = req.url.encodedPath
            when {
                path == "/api/items/li_1" -> respond(
                    """{"id":"li_1","media":{"audioFiles":[{"index":1,"ino":"11","metadata":{"filename":"One.mp3","size":${body.size}}}]}}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")
                )
                path.endsWith("/download") && calls.incrementAndGet() == 1 -> {
                    val stalled = CoroutineScope(Dispatchers.IO).writer {
                        channel.writeFully(body.copyOfRange(0, 700))
                        channel.flush()
                        awaitCancellation()
                    }.channel
                    respond(stalled, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
                }
                path.endsWith("/download") -> {
                    delay(50) // a slow answer gives an old job time to write, if it still could
                    serve(body, req)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val dao = InMemoryDownloadDao()
        val servers = FakeServers(testServer())
        val authorizer = fake.authorizer(servers)
        val location = object : DownloadLocation {
            override suspend fun rootDir() = root
            override suspend fun freeBytes() = Long.MAX_VALUE
        }
        val books = FakeBookDao(
            BookEntity(
                id = bookId, title = "T", author = "A", narrator = "N", durationMs = 1, chaptersJson = "[]",
                streamUrl = "", coverUrl = null, source = "ABS", serverId = "srv1", cachedAt = 0
            )
        )
        val downloader = BookDownloader(
            dao, books, servers, fake.api, authorizer, FileDownloader(fake.downloadClient, authorizer), location,
            sleep = {}, persistIntervalMs = 3_600_000,
        )
        lateinit var controller: DefaultDownloadController
        val scheduler = RunningScheduler(downloader) { controller }

        init {
            controller = DefaultDownloadController(dao, books, FakeAppSettings(), location, scheduler, downloader)
        }

        suspend fun awaitFirstBytes() = withTimeout(10_000) {
            while (calls.get() == 0) delay(10)
            delay(300)
        }
    }

    @Test
    fun `cancel returns only after the old job stopped, and nothing comes back after the delete`() = runBlocking {
        val rig = Rig()
        rig.controller.enqueue(bookId)
        rig.awaitFirstBytes()
        val oldJob = rig.scheduler.jobs.getValue(bookId)

        rig.controller.cancel(bookId)

        assertTrue(oldJob.isCompleted, "cancel returned while the job still ran")
        assertFalse(dir.exists())
        assertTrue(rig.dao.downloads.value.isEmpty())
        assertTrue(rig.dao.files.value.isEmpty())
        delay(300)
        assertFalse(dir.exists())
        assertTrue(rig.dao.downloads.value.isEmpty())
        assertTrue(rig.dao.files.value.isEmpty())
    }

    @Test
    fun `a quick pause and resume continues at the saved byte once the old job has let go`() = runBlocking {
        val rig = Rig()
        rig.controller.enqueue(bookId)
        rig.awaitFirstBytes()

        rig.controller.pause(bookId)
        rig.controller.resume(bookId)
        withTimeout(10_000) { while (rig.dao.get(bookId)?.status != DownloadStatus.DONE.name) delay(20) }

        val requests = rig.fake.fileRequests.filter { it.url.encodedPath.endsWith("/download") }
        assertEquals(2, requests.size)
        assertEquals("bytes=700-", requests[1].headers[HttpHeaders.Range])
        assertContentEquals(body, File(dir, "11.mp3").readBytes())
        assertEquals(body.size.toLong(), rig.dao.files.value.single().doneBytes)
    }
}
