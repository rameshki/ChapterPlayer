package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.data.repository.InMemoryDownloadDao
import com.chapterplayer.domain.downloads.BookProgress
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadOverviewTest {

    private fun row(id: String, status: DownloadStatus, total: Long = 0, error: String? = null) =
        DownloadEntity(id, "s1", status.name, total, error, 0)

    private fun file(book: String, ino: String, total: Long, done: Long, order: Int = 0) =
        DownloadFileEntity(book, ino, "$ino.mp3", "/x/$book/$ino.mp3", total, done, order)

    private class FakeLive : LiveDownloadProgress {
        val flow = MutableStateFlow<Map<String, BookProgress>>(emptyMap())
        override fun observeAll(): Flow<Map<String, BookProgress>> = flow
    }

    @Test
    fun `fraction and bytes come from the files and speed and time left from the live progress`() {
        val rows = downloadRows(
            listOf(row("a", DownloadStatus.DOWNLOADING, total = 400)),
            listOf(file("a", "1", 100, 100), file("a", "2", 300, 50, order = 1)),
            mapOf("a" to BookProgress(0.9f, 25.0, 10))
        )
        assertEquals(listOf(DownloadRow("a", DownloadStatus.DOWNLOADING, 150f / 400f, 150, 400, 25.0, 10, null)), rows)
    }

    @Test
    fun `before the files are planned the total is the download's own and nothing is done`() {
        val rows = downloadRows(listOf(row("a", DownloadStatus.QUEUED, total = 0)), emptyList(), emptyMap())
        assertEquals(DownloadRow("a", DownloadStatus.QUEUED, 0f, 0, 0, 0.0, null, null), rows.single())
    }

    @Test
    fun `live speed is ignored unless the book is downloading`() {
        val live = mapOf("a" to BookProgress(0.5f, 99.0, 5), "b" to BookProgress(1f, 99.0, 0))
        val rows = downloadRows(
            listOf(row("a", DownloadStatus.PAUSED), row("b", DownloadStatus.DONE)),
            listOf(file("a", "1", 100, 50), file("b", "1", 100, 100)),
            live
        )
        assertEquals(0.0, rows[0].bytesPerSecond)
        assertNull(rows[0].etaSeconds)
        assertEquals(0.0, rows[1].bytesPerSecond)
        assertEquals(1f, rows[1].fraction)
    }

    @Test
    fun `the error shows only for a failed download, and an unknown status reads as failed`() {
        val rows = downloadRows(
            listOf(row("a", DownloadStatus.FAILED, error = "Not enough space"), row("b", DownloadStatus.QUEUED, error = "old"),
                DownloadEntity("c", "s1", "WHATEVER", 0, null, 0)),
            emptyList(), emptyMap()
        )
        assertEquals("Not enough space", rows[0].error)
        assertNull(rows[1].error)
        assertEquals(DownloadStatus.FAILED, rows[2].status)
    }

    @Test
    fun `done bytes over the size of a file are not counted`() {
        val rows = downloadRows(listOf(row("a", DownloadStatus.DOWNLOADING)), listOf(file("a", "1", 100, 150)), emptyMap())
        assertEquals(100, rows.single().doneBytes)
        assertEquals(1f, rows.single().fraction)
    }

    @Test
    fun `the overview follows the tables, one book, and the storage used`() = runTest {
        val dao = InMemoryDownloadDao(
            listOf(row("a", DownloadStatus.DOWNLOADING), row("b", DownloadStatus.DONE)),
            listOf(file("a", "1", 100, 40), file("b", "1", 200, 200))
        )
        val live = FakeLive()
        val overview = RoomDownloadOverview(dao, live)

        assertEquals(listOf("a", "b"), overview.observe().first().map { it.bookId })
        assertEquals(240L, overview.storageUsedBytes().first())
        assertNull(overview.observeBook("zzz").first())

        live.flow.value = mapOf("a" to BookProgress(0.4f, 10.0, 6))
        assertEquals(6L, overview.observeBook("a").first()?.etaSeconds)

        dao.deleteBook("a")
        assertNull(overview.observeBook("a").first())
        assertEquals(200L, overview.storageUsedBytes().first())
    }
}
