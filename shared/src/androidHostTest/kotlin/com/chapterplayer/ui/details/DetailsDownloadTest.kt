package com.chapterplayer.ui.details

import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.downloadRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailsDownloadTest {

    private fun of(
        status: DownloadStatus?,
        online: Boolean = true,
        size: Long? = null,
        fraction: Float = 0f,
        eta: Long? = null,
        error: String? = null,
        total: Long = 0,
    ) = detailsDownload(
        status?.let { downloadRow("a", it, fraction = fraction, etaSeconds = eta, error = error, totalBytes = total) },
        online, size, confirmingRemove = false
    )

    @Test
    fun `Download names no source and adds no note`() {
        val idle = detailsDownload(null, true, 1_240_000_000, confirmingRemove = false)
        assertEquals("Download (1.2 GB)", idle.buttonLabel)
        assertEquals(null, idle.note)
    }

    @Test
    fun `a book not on the phone offers Download with its size when known`() {
        val idle = of(null, size = 1_240_000_000)
        assertEquals(DetailsDownloadPhase.IDLE, idle.phase)
        assertEquals("Download (1.2 GB)", idle.buttonLabel)
        assertTrue(idle.canStart)
        assertNull(idle.disabledReason)
        assertEquals("Download", of(null).buttonLabel)
    }

    @Test
    fun `offline, Download and Try again are disabled with the connection wording`() {
        val idle = of(null, online = false)
        assertFalse(idle.canStart)
        assertEquals("Needs a connection to download", idle.disabledReason)
        val failed = of(DownloadStatus.FAILED, online = false, error = "x")
        assertFalse(failed.canStart)
        assertEquals("Needs a connection to download", failed.disabledReason)
    }

    @Test
    fun `without a downloader Download and Try again are off with the platform wording, online or not`() {
        for (online in listOf(true, false)) {
            val idle = detailsDownload(null, online, null, confirmingRemove = false, available = false)
            assertEquals(DetailsDownloadPhase.IDLE, idle.phase)
            assertFalse(idle.canStart)
            assertEquals("Downloads are not available on iOS yet", idle.disabledReason)
            val failed = detailsDownload(downloadRow("a", DownloadStatus.FAILED), online, null, confirmingRemove = false, available = false)
            assertFalse(failed.canStart)
            assertEquals("Downloads are not available on iOS yet", failed.disabledReason)
        }
    }

    @Test
    fun `downloading shows the percent and the time left, or Calculating while unknown`() {
        val running = of(DownloadStatus.DOWNLOADING, fraction = 0.42f, eta = 3 * 3600 + 10 * 60)
        assertEquals(DetailsDownloadPhase.DOWNLOADING, running.phase)
        assertEquals(42, running.percent)
        assertEquals("42% · 3h 10m left", running.statusText)
        assertEquals("42% · Calculating", of(DownloadStatus.DOWNLOADING, fraction = 0.42f).statusText)
    }

    @Test
    fun `queued, paused and failed say so`() {
        assertEquals("Queued", of(DownloadStatus.QUEUED).statusText)
        assertEquals("Queued · 10%", of(DownloadStatus.QUEUED, fraction = 0.1f).statusText)
        assertEquals("Paused · 42%", of(DownloadStatus.PAUSED, fraction = 0.42f).statusText)
        val failed = of(DownloadStatus.FAILED, error = "Not enough space on this phone")
        assertEquals(DetailsDownloadPhase.FAILED, failed.phase)
        assertEquals("Not enough space on this phone", failed.statusText)
        assertEquals("Try again", failed.buttonLabel)
        assertEquals("The download failed", of(DownloadStatus.FAILED).statusText)
    }

    @Test
    fun `a finished download says how much it holds, even offline`() {
        val done = of(DownloadStatus.DONE, online = false, total = 2_000_000_000, fraction = 1f)
        assertEquals(DetailsDownloadPhase.DONE, done.phase)
        assertEquals("Downloaded · 2 GB", done.statusText)
        assertEquals(100, done.percent)
        assertNull(done.disabledReason)
    }
}
