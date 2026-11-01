package com.chapterplayer.data

import com.chapterplayer.data.downloads.DownloadRow
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.offeredChips
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.settings.downloadsSummary
import com.chapterplayer.navigation.Screen
import com.chapterplayer.navigation.visibleTabs
import com.chapterplayer.ui.components.DEFAULT_PLATFORM_FEATURES
import com.chapterplayer.ui.details.detailsDownload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What each feature set does to the shared screens' rules (tabs, chips, the Download control, the settings row). */
class PlatformFeaturesTest {

    private fun offersDownloads(features: PlatformFeatures): Boolean {
        val tab = visibleTabs(features).any { it.screen == Screen.Downloads }
        val chip = FilterChip.DOWNLOADED in offeredChips(features.downloads)
        val control = detailsDownload(null, online = true, knownSizeBytes = null, confirmingRemove = false, available = features.downloads).canStart
        val failed = DownloadRow("a", DownloadStatus.FAILED, 0f, 0L, 0L, 0.0, null, "x")
        val retry = detailsDownload(failed, online = true, knownSizeBytes = null, confirmingRemove = false, available = features.downloads).canStart
        // The four agree: a platform either offers downloads everywhere or nowhere.
        assertEquals(setOf(tab), setOf(tab, chip, control, retry))
        return tab
    }

    @Test
    fun `Android offers the Downloads tab - the Downloaded chip - Download and its settings summary`() {
        assertTrue(offersDownloads(PlatformFeatures.Full))
        assertEquals("Wi-Fi only · Internal storage · No limit", downloadsSummary(true, null, null, available = PlatformFeatures.Full.downloads))
    }

    @Test
    fun `the iPhone build offers no download anywhere and says so in Settings`() {
        assertFalse(offersDownloads(PlatformFeatures.IosBrowsingOnly))
        assertEquals("Not available on iOS yet", downloadsSummary(true, null, null, available = PlatformFeatures.IosBrowsingOnly.downloads))
        assertEquals(listOf("Home", "Library", "Search"), visibleTabs(PlatformFeatures.IosBrowsingOnly).map { it.label })
    }

    @Test
    fun `the streaming iPhone build plays but still offers no download and no car`() {
        assertTrue(PlatformFeatures.IosStreaming.playback)
        assertFalse(PlatformFeatures.IosStreaming.car)
        assertFalse(offersDownloads(PlatformFeatures.IosStreaming))
        assertEquals(listOf("Home", "Library", "Search"), visibleTabs(PlatformFeatures.IosStreaming).map { it.label })
    }

    @Test
    fun `a screen outside the provider fails closed - no downloads - no player - no car`() {
        assertFalse(offersDownloads(DEFAULT_PLATFORM_FEATURES))
        assertFalse(DEFAULT_PLATFORM_FEATURES.playback)
        assertFalse(DEFAULT_PLATFORM_FEATURES.car)
    }
}
