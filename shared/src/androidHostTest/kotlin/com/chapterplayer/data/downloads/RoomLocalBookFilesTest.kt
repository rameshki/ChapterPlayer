package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.data.repository.InMemoryDownloadDao
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomLocalBookFilesTest {

    private val book = "abs_a"

    private fun download(status: DownloadStatus) = DownloadEntity(book, "s1", status.name, 300, null, 1L)

    private fun file(ino: String, order: Int, bytes: Long = 100, durationMs: Long = 0, mime: String = "") =
        DownloadFileEntity(book, ino, "$ino.mp3", "/d/$book/$ino.mp3", bytes, bytes, order, durationMs, mime)

    private fun rig(status: DownloadStatus?, files: List<DownloadFileEntity>, onDisk: Map<String, Long>): Pair<InMemoryDownloadDao, RoomLocalBookFiles> {
        val dao = InMemoryDownloadDao(listOfNotNull(status?.let(::download)), files)
        return dao to RoomLocalBookFiles(dao, fileLength = { onDisk[it] }, nowMs = { 42L })
    }

    @Test
    fun `a finished download with every file whole gives its files in book order`() = runTest {
        val files = listOf(file("12", 1, durationMs = 2_000, mime = "audio/mpeg"), file("11", 0, durationMs = 1_000))
        val (_, local) = rig(DownloadStatus.DONE, files, files.associate { it.path to it.totalBytes })

        assertEquals(
            listOf(
                LocalFile("11", "/d/abs_a/11.mp3", 1_000, ""),
                LocalFile("12", "/d/abs_a/12.mp3", 2_000, "audio/mpeg"),
            ),
            local.complete(book),
        )
    }

    @Test
    fun `a book that is not downloaded or still downloading has no local files`() = runTest {
        val files = listOf(file("11", 0))
        for (status in listOf(null, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.QUEUED, DownloadStatus.FAILED)) {
            val (dao, local) = rig(status, files, files.associate { it.path to it.totalBytes })
            assertNull(local.complete(book), "$status")
            assertEquals(status?.name, dao.get(book)?.status, "$status is left alone")
        }
    }

    @Test
    fun `a finished download with a file gone is marked failed and the missing file is to be fetched again`() = runTest {
        val files = listOf(file("11", 0), file("12", 1))
        val (dao, local) = rig(DownloadStatus.DONE, files, mapOf("/d/abs_a/11.mp3" to 100L))

        assertNull(local.complete(book))

        val row = dao.get(book)!!
        assertEquals(DownloadStatus.FAILED.name, row.status)
        assertEquals(MISSING_FILES_TEXT, row.error)
        assertEquals(42L, row.updatedAt)
        val byIno = dao.getFiles(book).associateBy { it.ino }
        assertEquals(100L, byIno.getValue("11").doneBytes, "the whole file is kept")
        assertEquals(0L, byIno.getValue("12").doneBytes, "the missing file downloads again")
    }

    @Test
    fun `a file of the wrong size counts as missing`() = runTest {
        val files = listOf(file("11", 0))
        val (dao, local) = rig(DownloadStatus.DONE, files, mapOf("/d/abs_a/11.mp3" to 60L))

        assertNull(local.complete(book))
        assertEquals(DownloadStatus.FAILED.name, dao.get(book)!!.status)
        assertEquals(0L, dao.getFiles(book).single().doneBytes)
    }

    @Test
    fun `a disk that cannot be read is treated as nothing on the phone without changing the row`() = runTest {
        val files = listOf(file("11", 0))
        val dao = InMemoryDownloadDao(listOf(download(DownloadStatus.DONE)), files)
        val local = RoomLocalBookFiles(dao, fileLength = { error("storage gone") }, nowMs = { 42L })

        assertNull(local.complete(book))
        assertEquals(DownloadStatus.DONE.name, dao.get(book)!!.status)
        assertEquals(100L, dao.getFiles(book).single().doneBytes)
    }

    @Test
    fun `lengths learnt from the server fill in only files that have none`() = runTest {
        val files = listOf(file("11", 0, durationMs = 0), file("12", 1, durationMs = 5_000))
        val (dao, local) = rig(DownloadStatus.DONE, files, emptyMap())

        local.saveDurations(book, mapOf("11" to 1_500L, "12" to 9_999L, "99" to 1L))

        val byIno = dao.getFiles(book).associateBy { it.ino }
        assertEquals(1_500L, byIno.getValue("11").durationMs)
        assertEquals(5_000L, byIno.getValue("12").durationMs)
    }
}
