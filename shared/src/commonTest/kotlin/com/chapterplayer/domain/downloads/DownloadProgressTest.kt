package com.chapterplayer.domain.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadProgressTest {

    @Test
    fun `book progress adds up the files and divides what is left by the speed`() {
        val progress = bookProgress(listOf(FileProgress(1_000, 1_000), FileProgress(3_000, 1_000)), bytesPerSecond = 100.0)
        assertEquals(0.5f, progress.fraction)
        assertEquals(20L, progress.etaSeconds)
    }

    @Test
    fun `without a speed the time left is unknown - and a finished book has none left`() {
        assertNull(bookProgress(listOf(FileProgress(10, 5)), 0.0).etaSeconds)
        assertEquals(0L, bookProgress(listOf(FileProgress(10, 10)), 0.0).etaSeconds)
    }
}
