package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressRulesTest {
    @Test
    fun `finished means within five seconds of the end`() {
        assertFalse(isFinishedAt(94_999L, 100_000L))
        assertTrue(isFinishedAt(95_000L, 100_000L))
        assertTrue(isFinishedAt(100_000L, 100_000L))
        assertTrue(isFinishedAt(120_000L, 100_000L))
    }

    @Test
    fun `an unknown length is never finished`() {
        assertFalse(isFinishedAt(10_000L, 0L))
        assertFalse(isFinishedAt(0L, -1L))
    }

    @Test
    fun `progress is saved for a known book that was played`() {
        assertTrue(shouldSaveProgress("a", 1L, 100_000L))
        assertTrue(shouldSaveProgress("a", 100_000L, 100_000L))
    }

    @Test
    fun `nothing is saved at position zero`() {
        assertFalse(shouldSaveProgress("a", 0L, 100_000L))
        assertFalse(shouldSaveProgress("a", -5L, 100_000L))
    }

    @Test
    fun `nothing is saved without a book id or a length`() {
        assertFalse(shouldSaveProgress(null, 5_000L, 100_000L))
        assertFalse(shouldSaveProgress("", 5_000L, 100_000L))
        assertFalse(shouldSaveProgress("a", 5_000L, 0L))
    }
}
