package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.data.repository.InMemoryDownloadDao
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.FakeConnectivity
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadLengthFillerTest {

    private fun download(bookId: String, status: DownloadStatus) = DownloadEntity(bookId, "srv1", status.name, 300, null, 1L)

    private fun file(bookId: String, ino: String, order: Int, durationMs: Long = 0) =
        DownloadFileEntity(bookId, ino, "$ino.mp3", "/d/$bookId/$ino.mp3", 100, 100, order, durationMs)

    /** Synthetic item answers: book a has three files with lengths, book b is broken on the server. */
    private val abs = FakeAbs { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/api/items/li_b") -> respond("no", HttpStatusCode.InternalServerError)
            path.endsWith("/api/items/li_a") -> respond(
                """{"id":"li_a","media":{"audioFiles":[
                    {"index":1,"ino":"11","duration":64.0,"metadata":{"filename":"a1.mp3","size":100}},
                    {"index":2,"ino":"12","duration":128.5,"metadata":{"filename":"a2.mp3","size":100}},
                    {"index":3,"ino":"13","metadata":{"filename":"a3.mp3","size":100}}]}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
            path.endsWith("/api/items/li_c") -> respond(
                """{"id":"li_c","media":{"audioFiles":[{"index":1,"ino":"31","duration":10.0,"metadata":{"filename":"c.mp3","size":100}}]}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private val noDisk = object : LocalDisk {
        override fun fileLength(path: String): Long? = null
        override fun folderExists(path: String): Boolean = true
    }

    private fun filler(dao: InMemoryDownloadDao, connectivity: FakeConnectivity = FakeConnectivity(), scope: kotlinx.coroutines.CoroutineScope) =
        FakeServers(testServer()).let { servers ->
            DownloadLengthFiller(
                dao, servers, listOf(AbsDownloadSource(abs.api, abs.authorizer(servers))),
                RoomLocalBookFiles(dao, noDisk, Dispatchers.Unconfined, nowMs = { 1L }), connectivity, scope,
            )
        }

    private fun lengths(dao: InMemoryDownloadDao, bookId: String) =
        dao.files.value.filter { it.bookId == bookId }.sortedBy { it.sortOrder }.map { it.durationMs }

    @Test
    fun `finished downloads get the server's lengths for the files that have none`() = runTest {
        val dao = InMemoryDownloadDao(
            listOf(download("abs_li_a", DownloadStatus.DONE)),
            listOf(file("abs_li_a", "11", 0), file("abs_li_a", "12", 1, durationMs = 7_000), file("abs_li_a", "13", 2)),
        )

        filler(dao, scope = backgroundScope).fill()

        assertEquals(listOf(64_000L, 7_000L, 0L), lengths(dao, "abs_li_a"))
    }

    @Test
    fun `only finished downloads missing a length ask the server`() = runTest {
        val dao = InMemoryDownloadDao(
            listOf(download("abs_li_a", DownloadStatus.DOWNLOADING), download("abs_li_c", DownloadStatus.DONE)),
            listOf(file("abs_li_a", "11", 0), file("abs_li_c", "31", 0, durationMs = 5_000)),
        )

        filler(dao, scope = backgroundScope).fill()

        assertEquals(emptyList(), abs.fileRequests.map { it.url.encodedPath })
        assertEquals(listOf(0L), lengths(dao, "abs_li_a"))
    }

    @Test
    fun `one book the server cannot answer does not stop the others`() = runTest {
        val dao = InMemoryDownloadDao(
            listOf(download("abs_li_b", DownloadStatus.DONE), download("abs_li_c", DownloadStatus.DONE)),
            listOf(file("abs_li_b", "21", 0), file("abs_li_c", "31", 0)),
        )

        filler(dao, scope = backgroundScope).fill()

        assertEquals(listOf(0L), lengths(dao, "abs_li_b"))
        assertEquals(listOf(10_000L), lengths(dao, "abs_li_c"))
    }

    @Test
    fun `the lengths are fetched when the phone comes online`() = runTest {
        val dao = InMemoryDownloadDao(listOf(download("abs_li_c", DownloadStatus.DONE)), listOf(file("abs_li_c", "31", 0)))
        val connectivity = FakeConnectivity(online = false)
        filler(dao, connectivity, backgroundScope).start()

        advanceUntilIdle()
        assertEquals(listOf(0L), lengths(dao, "abs_li_c"))

        connectivity.online.value = true
        // The fake server answers on the HTTP client's own threads, so wait in real time.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (lengths(dao, "abs_li_c") != listOf(10_000L)) delay(10) }
        }
        assertEquals(listOf(10_000L), lengths(dao, "abs_li_c"))
    }
}
