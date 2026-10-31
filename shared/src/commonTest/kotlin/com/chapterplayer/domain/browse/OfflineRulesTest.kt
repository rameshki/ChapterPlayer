package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineRulesTest {

    @Test
    fun `a downloaded book is playable in every situation`() {
        for (online in listOf(true, false)) for (only in listOf(true, false)) {
            assertTrue(isPlayable(DownloadState.DOWNLOADED, online, only), "online=$online downloadedOnly=$only")
        }
    }

    @Test
    fun `a book that is not downloaded plays only while online and not in downloaded only mode`() {
        assertTrue(isPlayable(DownloadState.NOT_DOWNLOADED, online = true, downloadedOnly = false))
        assertFalse(isPlayable(DownloadState.NOT_DOWNLOADED, online = false, downloadedOnly = false))
        assertFalse(isPlayable(DownloadState.NOT_DOWNLOADED, online = true, downloadedOnly = true))
        assertFalse(isPlayable(DownloadState.NOT_DOWNLOADED, online = false, downloadedOnly = true))
    }

    @Test
    fun `a book that is still downloading is treated like one that is not downloaded`() {
        assertTrue(isPlayable(DownloadState.DOWNLOADING, online = true, downloadedOnly = false))
        assertFalse(isPlayable(DownloadState.DOWNLOADING, online = false, downloadedOnly = false))
        assertFalse(isPlayable(DownloadState.DOWNLOADING, online = true, downloadedOnly = true))
    }

    @Test
    fun `dimmed is exactly the opposite of playable for every combination`() {
        for (state in DownloadState.values()) for (online in listOf(true, false)) for (only in listOf(true, false)) {
            assertEquals(!isPlayable(state, online, only), isDimmed(state, online, only), "$state online=$online only=$only")
        }
    }

    @Test
    fun `nothing is dimmed while online with downloaded only off`() {
        for (state in DownloadState.values()) assertFalse(isDimmed(state, online = true, downloadedOnly = false), "$state")
    }

    @Test
    fun `books not downloaded are dimmed when the phone is offline`() {
        assertTrue(isDimmed(DownloadState.NOT_DOWNLOADED, online = false, downloadedOnly = false))
        assertFalse(isDimmed(DownloadState.DOWNLOADED, online = false, downloadedOnly = false))
    }

    @Test
    fun `the banner shows while offline or while downloaded only is on`() {
        assertTrue(showOfflineBanner(online = false, downloadedOnly = false))
        assertTrue(showOfflineBanner(online = false, downloadedOnly = true))
        assertTrue(showOfflineBanner(online = true, downloadedOnly = true))
    }

    @Test
    fun `the banner is hidden while online with downloaded only off`() {
        assertFalse(showOfflineBanner(online = true, downloadedOnly = false))
    }
}
