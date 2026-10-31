package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterLogicTest {

    private fun ch(id: Int, start: Long, end: Long) = Chapter(id = id, title = "Chapter $id", startMs = start, endMs = end)

    private val three = listOf(ch(1, 0, 1_000), ch(2, 1_000, 2_000), ch(3, 2_000, 3_000))

    // ---- currentChapterIndex ----

    @Test
    fun `no chapters means no current chapter`() {
        assertNull(currentChapterIndex(emptyList(), 500))
    }

    @Test
    fun `a position inside a chapter selects it and a start is inclusive`() {
        assertEquals(0, currentChapterIndex(three, 0))
        assertEquals(0, currentChapterIndex(three, 999))
        assertEquals(1, currentChapterIndex(three, 1_000))
        assertEquals(1, currentChapterIndex(three, 1_999))
        assertEquals(2, currentChapterIndex(three, 2_500))
    }

    @Test
    fun `a position before the first chapter or below zero selects the first chapter`() {
        assertEquals(0, currentChapterIndex(listOf(ch(1, 500, 1_000), ch(2, 1_000, 2_000)), 100))
        assertEquals(0, currentChapterIndex(three, -50))
    }

    @Test
    fun `a position at or after the end of the last chapter selects the last chapter`() {
        assertEquals(2, currentChapterIndex(three, 3_000))
        assertEquals(2, currentChapterIndex(three, 99_999))
    }

    @Test
    fun `when chapters overlap the last chapter that has started wins`() {
        val overlapping = listOf(ch(1, 0, 2_000), ch(2, 1_000, 3_000))
        assertEquals(0, currentChapterIndex(overlapping, 500))
        assertEquals(1, currentChapterIndex(overlapping, 1_500))
    }

    @Test
    fun `in a gap between chapters the previous chapter stays current`() {
        val gap = listOf(ch(1, 0, 1_000), ch(2, 2_000, 3_000))
        assertEquals(0, currentChapterIndex(gap, 1_500))
        assertEquals(1, currentChapterIndex(gap, 2_000))
    }

    @Test
    fun `a single chapter is always current`() {
        val one = listOf(ch(1, 0, 5_000))
        assertEquals(0, currentChapterIndex(one, 0))
        assertEquals(0, currentChapterIndex(one, 4_999))
        assertEquals(0, currentChapterIndex(one, 9_000))
    }

    // ---- formatClock ----

    @Test
    fun `clock shows minutes and seconds under an hour`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:05", formatClock(5_000))
        assertEquals("1:05", formatClock(65_000))
        assertEquals("59:59", formatClock(3_599_000))
    }

    @Test
    fun `clock shows hours from one hour and never wraps`() {
        assertEquals("1:00:00", formatClock(3_600_000))
        assertEquals("1:01:01", formatClock(3_661_000))
        assertEquals("100:00:00", formatClock(360_000_000))
    }

    @Test
    fun `clock drops milliseconds and treats negative as zero`() {
        assertEquals("0:01", formatClock(1_999))
        assertEquals("0:00", formatClock(-5_000))
    }

    // ---- formatLength ----

    @Test
    fun `length reads in minutes under an hour`() {
        assertEquals("45 min", formatLength(45 * 60_000L))
        assertEquals("1 min", formatLength(60_000))
        assertEquals("59 min", formatLength(3_599_000))
    }

    @Test
    fun `length reads in hours and padded minutes`() {
        assertEquals("1 h", formatLength(3_600_000))
        assertEquals("1 h 05 min", formatLength(3_900_000))
        assertEquals("12 h 30 min", formatLength(12 * 3_600_000L + 30 * 60_000L))
    }

    @Test
    fun `length under a minute and zero or negative have their own words`() {
        assertEquals("under 1 min", formatLength(30_000))
        assertEquals("under 1 min", formatLength(59_999))
        assertEquals("0 min", formatLength(0))
        assertEquals("0 min", formatLength(-5))
    }

    // ---- formatTimeLeft ----

    @Test
    fun `time left is the remainder in length words followed by left`() {
        assertEquals("3 h 10 min left", formatTimeLeft(durationMs = 4 * 3_600_000L, positionMs = 50 * 60_000L))
    }

    @Test
    fun `time left says finished when nothing is left`() {
        assertEquals("finished", formatTimeLeft(durationMs = 10_000, positionMs = 10_000))
        assertEquals("finished", formatTimeLeft(durationMs = 10_000, positionMs = 99_000))
    }

    @Test
    fun `time left uses the whole length for a negative position and under 1 min near the end`() {
        assertEquals("4 h left", formatTimeLeft(durationMs = 4 * 3_600_000L, positionMs = -1_000))
        assertEquals("under 1 min left", formatTimeLeft(durationMs = 100_000, positionMs = 70_000))
    }
}
