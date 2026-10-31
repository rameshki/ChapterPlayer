package com.chapterplayer.domain.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class StorageTextTest {

    @Test
    fun `sizes read in whole megabytes below a gigabyte and tenths above`() {
        assertEquals("0 MB", formatBytes(0))
        assertEquals("1 MB", formatBytes(1))
        assertEquals("1 MB", formatBytes(1_400_000))
        assertEquals("350 MB", formatBytes(350_000_000))
        assertEquals("999 MB", formatBytes(999_400_000))
        assertEquals("1 GB", formatBytes(999_600_000))
        assertEquals("1.2 GB", formatBytes(1_240_000_000))
        assertEquals("2 GB", formatBytes(2_000_000_000))
        assertEquals("50 GB", formatBytes(50_000_000_000))
        assertEquals("0 MB", formatBytes(-5))
    }

    @Test
    fun `the storage limits offered are none and 1 to 50 GB`() {
        assertEquals(listOf(null, 1L, 2L, 5L, 10L, 20L, 50L).map { it?.times(GIGABYTE) }, STORAGE_LIMIT_OPTIONS)
        assertEquals("No limit", storageLimitLabel(null))
        assertEquals("5 GB", storageLimitLabel(5 * GIGABYTE))
    }

    @Test
    fun `the downloads header says what is used and what is free`() {
        assertEquals("1.2 GB used · Free: 30 GB", storageHeaderText(1_200_000_000, 30_000_000_000))
        assertEquals("0 MB used · Free: 512 MB", storageHeaderText(0, 512_000_000))
    }
}
