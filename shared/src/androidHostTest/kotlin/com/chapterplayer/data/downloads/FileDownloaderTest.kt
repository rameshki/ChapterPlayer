package com.chapterplayer.data.downloads

import com.chapterplayer.data.playback.SIGN_IN_AGAIN_TEXT
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDownloaderTest {

    private val root: File = Files.createTempDirectory("filedl").toFile()
    private val dir = File(root, "downloads/srv1/abs_li_1")
    private val body = content(10_000)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun request(recorded: Long = 0, total: Long = body.size.toLong(), ino: String = "77", fileName: String? = "Part 1.mp3") =
        FileRequest(
            source = absSource, server = testServer(), bookId = "abs_li_1", ino = ino, fileName = fileName,
            directory = dir, totalBytes = total, recordedDoneBytes = recorded
        )

    private fun downloader(
        fake: FakeAbs,
        stallMs: Long = 60_000,
        clock: () -> Long = System::currentTimeMillis,
        openPart: (File) -> FileOutputStream = { FileOutputStream(it, true) },
    ): FileDownloader {
        absSource = AbsDownloadSource(fake.api, fake.authorizer(FakeServers(testServer())))
        return FileDownloader(fake.downloadClient, stallMs = stallMs, nowMs = clock, openPart = openPart)
    }

    /** The source the requests go through; set by [downloader] for its fake server. */
    private lateinit var absSource: DownloadSource

    private val part get() = File(dir, "77.mp3.part")
    private val final get() = File(dir, "77.mp3")

    @Test
    fun `a full download lands in the final file with the bearer token and no range`() = runBlocking {
        val fake = FakeAbs { serve(body, it) }
        val progress = mutableListOf<Long>()

        val result = downloader(fake).download(request()) { progress += it }

        assertEquals(FileResult.Done(final), result)
        assertContentEquals(body, final.readBytes())
        assertFalse(part.exists())
        val sent = fake.fileRequests.single()
        assertEquals("http://abs.test/api/items/li_1/file/77/download", sent.url.toString())
        assertEquals("Bearer access-old", sent.headers[HttpHeaders.Authorization])
        assertNull(sent.headers[HttpHeaders.Range])
        assertEquals(body.size.toLong(), progress.last())
    }

    @Test
    fun `a part file resumes at the smaller of disk and record with a range header`() = runBlocking {
        dir.mkdirs()
        part.writeBytes(body.copyOfRange(0, 6_000)) // more on disk than recorded: the record wins
        val fake = FakeAbs { serve(body, it) }

        val result = downloader(fake).download(request(recorded = 4_000)) {}

        assertEquals(FileResult.Done(final), result)
        assertEquals("bytes=4000-", fake.fileRequests.single().headers[HttpHeaders.Range])
        assertContentEquals(body, final.readBytes())
    }

    @Test
    fun `a server that ignores the range and answers 200 restarts the file from zero`() = runBlocking {
        dir.mkdirs()
        part.writeBytes(body.copyOfRange(0, 3_000))
        val fake = FakeAbs {
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        }

        val result = downloader(fake).download(request(recorded = 3_000)) {}

        assertEquals(FileResult.Done(final), result)
        assertEquals("bytes=3000-", fake.fileRequests.single().headers[HttpHeaders.Range])
        assertContentEquals(body, final.readBytes()) // not appended after the 3000 bytes
    }

    @Test
    fun `a 206 that starts at another byte is not appended`() = runBlocking {
        dir.mkdirs()
        part.writeBytes(body.copyOfRange(0, 3_000))
        val fake = FakeAbs { req ->
            if (req.rangeStart() != null) respond(
                ByteReadChannel(body.copyOfRange(1_000, body.size)), HttpStatusCode.PartialContent,
                headersOf(HttpHeaders.ContentRange, "bytes 1000-9999/10000")
            ) else serve(body, req)
        }

        val result = downloader(fake).download(request(recorded = 3_000)) {}

        assertEquals(FileResult.Done(final), result)
        assertNull(fake.fileRequests.last().headers[HttpHeaders.Range])
        assertContentEquals(body, final.readBytes())
    }

    @Test
    fun `416 with the part file already complete finishes without downloading`() = runBlocking {
        dir.mkdirs()
        part.writeBytes(body)
        val fake = FakeAbs { respond("", HttpStatusCode.RequestedRangeNotSatisfiable) }

        // The record is behind the disk, so a range is asked for and the server says there is nothing left.
        val result = downloader(fake).download(request(recorded = body.size.toLong() - 1)) {}

        assertEquals(FileResult.Done(final), result)
        assertContentEquals(body, final.readBytes())
    }

    @Test
    fun `416 with an incomplete part file restarts from zero`() = runBlocking {
        dir.mkdirs()
        part.writeBytes(body.copyOfRange(0, 5_000))
        val fake = FakeAbs { req ->
            if (req.rangeStart() != null) respond("", HttpStatusCode.RequestedRangeNotSatisfiable) else serve(body, req)
        }

        val result = downloader(fake).download(request(recorded = 5_000)) {}

        assertEquals(FileResult.Done(final), result)
        assertEquals(listOf("bytes=5000-", null), fake.fileRequests.map { it.headers[HttpHeaders.Range] })
        assertContentEquals(body, final.readBytes())
    }

    @Test
    fun `a body cut short asks for a retry, and the next run resumes at the cut`() = runBlocking {
        var cut = true
        val fake = FakeAbs { req -> serve(body, req, cutAt = if (cut) 4_321 else null) }

        val first = downloader(fake).download(request()) {}
        assertEquals(FileResult.Retry(4_321), first)
        assertEquals(4_321L, part.length())
        assertFalse(final.exists())

        cut = false
        val second = downloader(fake).download(request(recorded = 4_321)) {}
        assertEquals(FileResult.Done(final), second)
        assertEquals("bytes=4321-", fake.fileRequests.last().headers[HttpHeaders.Range])
        assertContentEquals(body, final.readBytes())
    }

    @Test
    fun `no bytes for the stall time cancels the request and asks for a retry`() = runBlocking {
        val fake = FakeAbs {
            val stalled = CoroutineScope(Dispatchers.IO).writer {
                channel.writeFully(body.copyOfRange(0, 1_000))
                channel.flush()
                awaitCancellation()
            }.channel
            respond(stalled, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        }

        val result = withTimeout(10_000) { downloader(fake, stallMs = 300).download(request()) {} }

        assertEquals(FileResult.Retry(1_000), result)
        assertEquals(1_000L, part.length())
    }

    @Test
    fun `a server error or a dropped connection asks for a retry`() = runBlocking {
        val failing = FakeAbs { respond("busy", HttpStatusCode.ServiceUnavailable) }
        assertEquals(FileResult.Retry(0), downloader(failing).download(request()) {})

        val dropped = FakeAbs { throw java.io.IOException("connection reset") }
        assertEquals(FileResult.Retry(0), downloader(dropped).download(request()) {})
    }

    @Test
    fun `other client errors fail with a fixed message that holds no address or token`() = runBlocking {
        val fake = FakeAbs { respond("missing", HttpStatusCode.NotFound) }

        val result = downloader(fake).download(request()) {}

        val failed = assertIs<FileResult.Failed>(result)
        assertFalse(failed.signInRequired)
        assertFalse("abs.test" in failed.message)
        assertFalse("access" in failed.message)
        assertFalse("/api/" in failed.message)
    }

    @Test
    fun `a 401 refreshes once and retries with the new token`() = runBlocking {
        val fake = FakeAbs { req ->
            if (req.headers[HttpHeaders.Authorization] == "Bearer access-old") respond("", HttpStatusCode.Unauthorized)
            else serve(body, req)
        }

        val result = downloader(fake).download(request()) {}

        assertEquals(FileResult.Done(final), result)
        assertEquals(1, fake.refreshes)
        assertEquals(listOf("Bearer access-old", "Bearer access-new"), fake.fileRequests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun `a second 401 fails with sign in again and does not loop`() = runBlocking {
        val fake = FakeAbs { respond("", HttpStatusCode.Unauthorized) }

        val result = downloader(fake).download(request()) {}

        assertEquals(FileResult.Failed(SIGN_IN_AGAIN_TEXT, signInRequired = true), result)
        assertEquals(1, fake.refreshes)
        assertEquals(2, fake.fileRequests.size)
    }

    @Test
    fun `a redirect to another host is refused and never requested`() = runBlocking {
        val fake = FakeAbs { req ->
            if (req.url.host == "abs.test") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://elsewhere.test/f"))
            else serve(body, req)
        }

        val result = downloader(fake).download(request()) {}

        assertIs<FileResult.Failed>(result)
        assertTrue(fake.requests.none { it.url.host == "elsewhere.test" })
        assertFalse(final.exists())
    }

    @Test
    fun `a redirect on the same server is followed with the token`() = runBlocking {
        val fake = FakeAbs { req ->
            if (req.url.encodedPath.endsWith("/download")) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/files/77"))
            else serve(body, req)
        }

        val result = downloader(fake).download(request()) {}

        assertEquals(FileResult.Done(final), result)
        val followed = fake.fileRequests.last()
        assertEquals("http://abs.test/files/77", followed.url.toString())
        assertEquals("Bearer access-old", followed.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a file of the wrong size is deleted and fails`() = runBlocking {
        val fake = FakeAbs { serve(body, it) }

        val result = downloader(fake).download(request(total = 12_000)) {}

        assertIs<FileResult.Failed>(result)
        assertFalse(part.exists())
        assertFalse(final.exists())
    }

    @Test
    fun `a longer body than expected is deleted and fails`() = runBlocking {
        val fake = FakeAbs { respond(ByteReadChannel(body), HttpStatusCode.OK) } // no length header

        val result = downloader(fake).download(request(total = 8_000)) {}

        assertIs<FileResult.Failed>(result)
        assertFalse(part.exists())
    }

    @Test
    fun `a server file name or ino cannot leave the book folder`() = runBlocking {
        val fake = FakeAbs { serve(body, it) }

        val result = downloader(fake).download(request(ino = "../../../escape", fileName = "../../x.mp3")) {}

        val done = assertIs<FileResult.Done>(result)
        assertEquals(dir.canonicalFile, done.file.canonicalFile.parentFile)
        assertEquals("escape.mp3", done.file.name)
        assertFalse(File(root, "escape.mp3").exists())
    }

    @Test
    fun `an already finished file is not downloaded again`() = runBlocking {
        dir.mkdirs()
        final.writeBytes(body)
        val fake = FakeAbs { serve(body, it) }

        assertEquals(FileResult.Done(final), downloader(fake).download(request()) {})
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `progress is reported at most every half second and at the end`() = runBlocking {
        var now = 0L
        val fake = FakeAbs {
            val chunks = CoroutineScope(Dispatchers.IO).writer {
                for (i in 0 until 10) {
                    channel.writeFully(body.copyOfRange(i * 1_000, (i + 1) * 1_000))
                    channel.flush()
                    kotlinx.coroutines.delay(20)
                }
            }.channel
            respond(chunks, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        }
        val progress = mutableListOf<Long>()

        // The clock moves 100 ms per reading, so at most one report in five readings.
        downloader(fake, clock = { now.also { now += 100 } }).download(request()) { progress += it }

        assertTrue(progress.size <= 4, "too many reports: $progress")
        assertEquals(body.size.toLong(), progress.last())
    }

    @Test
    fun `a server that never sends the headers is given up after the stall time and retried`() = runBlocking {
        val fake = FakeAbs { awaitCancellation() }

        val result = withTimeout(10_000) { downloader(fake, stallMs = 300).download(request()) {} }

        assertEquals(FileResult.Retry(0), result)
    }

    @Test
    fun `a cover whose headers never come is given up`() = runBlocking {
        val fake = FakeAbs { awaitCancellation() }
        dir.mkdirs()

        val ok = withTimeout(10_000) {
            downloader(fake, stallMs = 300).downloadSmall(absSource, testServer(), "http://abs.test/api/items/li_1/cover", File(dir, "cover.jpg"))
        }

        assertFalse(ok)
        assertFalse(File(dir, "cover.jpg").exists())
    }

    @Test
    fun `a refresh the server answers with too many requests or an error is retried, not a sign-in failure`() = runBlocking {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            val fake = FakeAbs(refreshStatus = status) { respond("", HttpStatusCode.Unauthorized) }
            assertEquals(FileResult.Retry(0), downloader(fake).download(request()) {}, "refresh answered $status")
        }
    }

    @Test
    fun `a refresh the server refuses is a sign-in failure`() = runBlocking {
        val fake = FakeAbs(refreshStatus = HttpStatusCode.Forbidden) { respond("", HttpStatusCode.Unauthorized) }
        assertEquals(FileResult.Failed(SIGN_IN_AGAIN_TEXT, signInRequired = true), downloader(fake).download(request()) {})
    }

    @Test
    fun `a full disk fails with not enough space instead of retrying`() = runBlocking {
        val fake = FakeAbs { serve(body, it) }
        val full: (File) -> FileOutputStream = { file ->
            object : FileOutputStream(file, true) {
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
            }
        }

        val result = downloader(fake, openPart = full).download(request()) {}

        assertEquals(FileResult.Failed("Not enough space"), result)
    }

    @Test
    fun `a part file that cannot be written fails with a storage error instead of retrying`() = runBlocking {
        part.mkdirs() // a folder where the part file should be
        val fake = FakeAbs { serve(body, it) }

        val result = downloader(fake).download(request()) {}

        val failed = assertIs<FileResult.Failed>(result)
        assertEquals("Cannot write the download to storage", failed.message)
        assertFalse(failed.replan)
    }

    @Test
    fun `a missing file or a wrong size asks for the book to be planned again`() = runBlocking {
        val missing = FakeAbs { respond("", HttpStatusCode.NotFound) }
        assertTrue(assertIs<FileResult.Failed>(downloader(missing).download(request()) {}).replan)

        val sized = FakeAbs { serve(body, it) }
        assertTrue(assertIs<FileResult.Failed>(downloader(sized).download(request(total = 12_000)) {}).replan)

        val refused = FakeAbs { respond("", HttpStatusCode.Forbidden) }
        assertFalse(assertIs<FileResult.Failed>(downloader(refused).download(request()) {}).replan)
    }
}
