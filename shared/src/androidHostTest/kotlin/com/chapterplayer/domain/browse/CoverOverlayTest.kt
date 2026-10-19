package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverOverlayTest {
    private fun progress(pos: Long, finished: Boolean = false) =
        ReadingProgress("b", pos, 1000, 0, finished)

    @Test
    fun `an in-progress book shows the bar`() {
        val o = coverOverlay(progress(250), DownloadState.NOT_DOWNLOADED, false)
        assertEquals(0.25f, o.progressFraction)
        assertFalse(o.finished)
    }

    @Test
    fun `a finished book shows the check and no bar`() {
        val o = coverOverlay(progress(1000, finished = true), DownloadState.NOT_DOWNLOADED, false)
        assertTrue(o.finished)
        assertNull(o.progressFraction)
    }

    @Test
    fun `position zero or no progress shows no bar`() {
        assertNull(coverOverlay(progress(0), DownloadState.NOT_DOWNLOADED, false).progressFraction)
        assertNull(coverOverlay(null, DownloadState.NOT_DOWNLOADED, false).progressFraction)
    }

    @Test
    fun `download states map to their badges`() {
        assertEquals(DownloadBadgeKind.DOWNLOADED, coverOverlay(null, DownloadState.DOWNLOADED, false).badge)
        assertEquals(DownloadBadgeKind.DOWNLOADING, coverOverlay(null, DownloadState.DOWNLOADING, false).badge)
        assertEquals(DownloadBadgeKind.NONE, coverOverlay(null, DownloadState.NOT_DOWNLOADED, false).badge)
    }

    @Test
    fun `dimmed adds the cloud`() {
        assertTrue(coverOverlay(null, DownloadState.NOT_DOWNLOADED, true).cloud)
        assertFalse(coverOverlay(null, DownloadState.NOT_DOWNLOADED, false).cloud)
    }
}
