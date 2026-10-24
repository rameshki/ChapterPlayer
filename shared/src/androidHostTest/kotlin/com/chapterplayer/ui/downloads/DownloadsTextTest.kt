package com.chapterplayer.ui.downloads

import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.downloadRow
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsTextTest {

    @Test
    fun `each status has its line`() {
        assertEquals("42% · 3h 10m left", downloadStatusLine(downloadRow("a", DownloadStatus.DOWNLOADING, fraction = 0.42f, etaSeconds = 11_400)))
        assertEquals("42% · Calculating", downloadStatusLine(downloadRow("a", DownloadStatus.DOWNLOADING, fraction = 0.42f)))
        assertEquals("Queued", downloadStatusLine(downloadRow("a", DownloadStatus.QUEUED)))
        assertEquals("Paused · 42%", downloadStatusLine(downloadRow("a", DownloadStatus.PAUSED, fraction = 0.42f)))
        assertEquals("Failed", downloadStatusLine(downloadRow("a", DownloadStatus.FAILED, error = "No space")))
        assertEquals("1.2 GB", downloadStatusLine(downloadRow("a", DownloadStatus.DONE, fraction = 1f, totalBytes = 1_200_000_000)))
    }

    @Test
    fun `active downloads come first, then by title`() {
        val sorted = sortDownloadItems(
            listOf(
                item("1", "b book", DownloadStatus.DONE),
                item("2", "Zed", DownloadStatus.PAUSED),
                item("3", "A book", DownloadStatus.DONE),
                item("4", "alpha", DownloadStatus.DOWNLOADING),
                item("5", "Mid", DownloadStatus.FAILED),
            )
        )
        assertEquals(listOf("4", "5", "2", "3", "1"), sorted.map { it.bookId })
    }

    private fun item(id: String, title: String, status: DownloadStatus) =
        DownloadItem(id, null, title, "", status, 0, "", null)
}
