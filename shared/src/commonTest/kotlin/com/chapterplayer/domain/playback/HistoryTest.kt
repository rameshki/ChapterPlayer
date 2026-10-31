package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryTest {

    // ---- jumpedFrom ----

    @Test
    fun `normal playback is not a jump`() {
        assertNull(jumpedFrom(100_000, 0, 110_000, 10_000, 1.0f, isPlaying = true))
    }

    @Test
    fun `a big jump forward or back returns the position the listener left`() {
        assertEquals(100_000L, jumpedFrom(100_000, 0, 300_000, 10_000, 1.0f, isPlaying = true))
        assertEquals(100_000L, jumpedFrom(100_000, 0, 20_000, 10_000, 1.0f, isPlaying = true))
    }

    @Test
    fun `the jump threshold is inclusive`() {
        assertEquals(100_000L, jumpedFrom(100_000, 0, 170_000, 10_000, 1.0f, isPlaying = true))
        assertNull(jumpedFrom(100_000, 0, 169_999, 10_000, 1.0f, isPlaying = true))
        assertEquals(100_000L, jumpedFrom(100_000, 0, 130_000, 10_000, 1.0f, isPlaying = true, minJumpMs = 20_000))
    }

    @Test
    fun `while paused the expected position does not move`() {
        assertNull(jumpedFrom(100_000, 0, 100_500, 50_000, 1.0f, isPlaying = false))
        assertEquals(100_000L, jumpedFrom(100_000, 0, 200_000, 50_000, 1.0f, isPlaying = false))
    }

    @Test
    fun `playing faster moves the expected position faster`() {
        assertNull(jumpedFrom(100_000, 0, 115_000, 10_000, 1.5f, isPlaying = true))
        assertEquals(100_000L, jumpedFrom(100_000, 0, 175_000, 10_000, 1.5f, isPlaying = true))
    }

    @Test
    fun `a speed of zero or less counts as normal speed`() {
        assertNull(jumpedFrom(100_000, 0, 110_000, 10_000, 0.0f, isPlaying = true))
        assertNull(jumpedFrom(100_000, 0, 110_000, 10_000, -2.0f, isPlaying = true))
    }

    @Test
    fun `a clock that went backwards adds no expected time`() {
        assertNull(jumpedFrom(100_000, 50_000, 100_000, 10_000, 1.0f, isPlaying = true))
        assertEquals(100_000L, jumpedFrom(100_000, 50_000, 200_000, 10_000, 1.0f, isPlaying = true))
    }

    // ---- addToHistory ----

    private fun entry(position: Long, at: Long = 0, book: String = "a") = HistoryEntry(book, position, at)

    @Test
    fun `a new entry goes first`() {
        val list = addToHistory(listOf(entry(100_000, 1)), entry(500_000, 2))
        assertEquals(listOf(500_000L, 100_000L), list.map { it.positionMs })
    }

    @Test
    fun `an entry within five seconds of an existing one of the same book replaces it and moves to the front`() {
        val list = addToHistory(listOf(entry(900_000, 3), entry(100_000, 1)), entry(103_000, 4))
        assertEquals(listOf(103_000L, 900_000L), list.map { it.positionMs })
        assertEquals(4L, list.first().recordedAt)
    }

    @Test
    fun `the dedupe distance is inclusive and other books are not affected`() {
        assertEquals(1, addToHistory(listOf(entry(100_000)), entry(105_000)).size)
        assertEquals(2, addToHistory(listOf(entry(100_000)), entry(105_001)).size)
        assertEquals(2, addToHistory(listOf(entry(100_000, book = "b")), entry(100_000, book = "a")).size)
    }

    @Test
    fun `the history is trimmed to the newest entries`() {
        var list = emptyList<HistoryEntry>()
        for (i in 1..5) list = addToHistory(list, entry(i * 100_000L, i.toLong()), max = 3)
        assertEquals(listOf(500_000L, 400_000L, 300_000L), list.map { it.positionMs })
    }

    // ---- historyAgoLabel ----

    @Test
    fun `the ago label reads in minutes - hours and days`() {
        assertEquals("just now", historyAgoLabel(0, 0))
        assertEquals("just now", historyAgoLabel(0, 59_999))
        assertEquals("1 min ago", historyAgoLabel(0, 60_000))
        assertEquals("59 min ago", historyAgoLabel(0, 3_599_999))
        assertEquals("1 h ago", historyAgoLabel(0, 3_600_000))
        assertEquals("23 h ago", historyAgoLabel(0, 86_399_999))
        assertEquals("1 d ago", historyAgoLabel(0, 86_400_000))
        assertEquals("2 d ago", historyAgoLabel(0, 2 * 86_400_000L + 5))
    }

    @Test
    fun `a time in the future reads as just now`() {
        assertEquals("just now", historyAgoLabel(10_000, 5_000))
    }
}
