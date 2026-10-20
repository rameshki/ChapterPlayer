package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SleepTimerTest {

    private val chapters = listOf(
        Chapter(id = 0, title = "One", startMs = 0, endMs = 600_000),
        Chapter(id = 1, title = "Two", startMs = 600_000, endMs = 1_800_000),
    )

    @Test
    fun `off has no remaining time`() {
        assertNull(sleepRemainingMs(SleepTimer.Off, 0, 0, chapters, 1.0f))
    }

    @Test
    fun `after counts down the played time and never goes below zero`() {
        val timer = SleepTimer.After(30)
        assertEquals(1_800_000, sleepRemainingMs(timer, 0, 0, chapters, 1.0f))
        assertEquals(1_200_000, sleepRemainingMs(timer, 600_000, 0, chapters, 1.0f))
        assertEquals(0, sleepRemainingMs(timer, 1_800_000, 0, chapters, 1.0f))
        assertEquals(0, sleepRemainingMs(timer, 2_000_000, 0, chapters, 1.0f))
    }

    @Test
    fun `after does not depend on the speed or on the chapters`() {
        assertEquals(300_000, sleepRemainingMs(SleepTimer.After(5), 0, 0, emptyList(), 2.0f))
    }

    @Test
    fun `end of chapter is the time left in the current chapter`() {
        assertEquals(500_000, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 100_000, chapters, 1.0f))
        assertEquals(1, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 599_999, chapters, 1.0f))
        assertEquals(1_200_000, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 600_000, chapters, 1.0f))
    }

    @Test
    fun `end of chapter is wall clock time so it shrinks at higher speed and rounds up`() {
        assertEquals(250_000, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 100_000, chapters, 2.0f))
        assertEquals(733_334, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 700_000, chapters, 1.5f))
    }

    @Test
    fun `a speed of zero or less is treated as normal speed`() {
        assertEquals(500_000, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 100_000, chapters, 0.0f))
        assertEquals(500_000, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 100_000, chapters, -1.0f))
    }

    @Test
    fun `end of chapter without chapters has no remaining time`() {
        assertNull(sleepRemainingMs(SleepTimer.EndOfChapter, 0, 100_000, emptyList(), 1.0f))
    }

    @Test
    fun `end of chapter past the last chapter is zero`() {
        assertEquals(0, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 2_000_000, chapters, 1.0f))
    }
}
