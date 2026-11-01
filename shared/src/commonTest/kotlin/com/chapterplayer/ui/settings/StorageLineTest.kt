package com.chapterplayer.ui.settings

import com.chapterplayer.domain.downloads.formatBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageLineTest {

    @Test
    fun `with a location choice only the used space is shown`() {
        assertEquals("Used by downloads: ${formatBytes(2048)}", storageLine(2048, null))
    }

    @Test
    fun `with one place its free space follows`() {
        val one = DownloadLocationsUi(options = listOf(LocationOption("internal", "This iPhone", "20 GB free")))
        assertEquals("Used by downloads: ${formatBytes(2048)} · This iPhone: 20 GB free", storageLine(2048, one))
    }

    @Test
    fun `before the place is read only the used space is shown`() {
        assertEquals("Used by downloads: ${formatBytes(0)}", storageLine(0, DownloadLocationsUi()))
    }
}
