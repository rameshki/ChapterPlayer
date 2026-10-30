package com.chapterplayer.data.downloads

import com.chapterplayer.data.permissions.NoNotificationPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotYetOnIosDownloadsTest {

    @Test
    fun `starting or resuming a download fails with the text the screens show`() = runTest {
        val controller = NotYetOnIosDownloadController()
        assertEquals("Downloads are not available on iOS yet", assertFailsWith<DownloadsNotAvailableException> { controller.enqueue("b1") }.message)
        assertEquals(DOWNLOADS_NOT_ON_IOS_TEXT, assertFailsWith<DownloadsNotAvailableException> { controller.resume("b1") }.message)
    }

    @Test
    fun `pause and cancel and remove and the Wi-Fi setting do nothing`() = runTest {
        val controller = NotYetOnIosDownloadController()
        controller.pause("b1")
        controller.cancel("b1")
        controller.remove("b1")
        controller.reapplyWifiSetting()
    }

    @Test
    fun `there are no downloaded files and no storage and no live progress and no notification prompt`() = runTest {
        assertNull(NoLocalBookFiles.complete("b1"))
        NoLocalBookFiles.saveDurations("b1", mapOf("ino" to 1_000L))
        assertNull(NoLocalBookFiles.complete("b1"))
        assertEquals(0L, NoDownloadStorage.freeBytes())
        assertTrue(NoDownloadStorage.volumes().isEmpty())
        assertTrue(NoLiveDownloadProgress.observeAll().first().isEmpty())
        assertFalse(NoNotificationPermission.ensure())
    }
}
