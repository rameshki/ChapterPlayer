package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NowPlayingTest {

    private val chapters = listOf(
        Chapter(id = 1, title = "One", startMs = 0, endMs = 10_000),
        Chapter(id = 2, title = "Two", startMs = 10_000, endMs = 40_000),
        Chapter(id = 3, title = "Three", startMs = 40_000, endMs = 100_000),
    )

    // ---- fractions ----

    @Test
    fun `chapter fraction is the position inside the current chapter`() {
        assertEquals(0f, chapterFraction(chapters, 0))
        assertEquals(0.5f, chapterFraction(chapters, 5_000))
        assertEquals(0f, chapterFraction(chapters, 10_000))
        assertEquals(0.5f, chapterFraction(chapters, 25_000))
    }

    @Test
    fun `chapter fraction is null without chapters and clamps at both ends`() {
        assertNull(chapterFraction(emptyList(), 5_000))
        assertEquals(1f, chapterFraction(chapters, 150_000))
        assertEquals(0f, chapterFraction(chapters, -5_000))
    }

    @Test
    fun `a chapter with no duration gives zero`() {
        assertEquals(0f, chapterFraction(listOf(Chapter(1, "a", 1_000, 1_000)), 1_000))
    }

    @Test
    fun `book fraction is position over duration and clamped`() {
        assertEquals(0.5f, bookFraction(50, 100))
        assertEquals(0f, bookFraction(0, 0))
        assertEquals(0f, bookFraction(10, -5))
        assertEquals(1f, bookFraction(200, 100))
        assertEquals(0f, bookFraction(-5, 100))
    }

    // ---- labels ----

    @Test
    fun `speed label drops trailing zeros and keeps at most two decimals`() {
        assertEquals("1x", speedLabel(1.0f))
        assertEquals("1.25x", speedLabel(1.25f))
        assertEquals("0.5x", speedLabel(0.5f))
        assertEquals("0.75x", speedLabel(0.75f))
        assertEquals("1.5x", speedLabel(1.5f))
        assertEquals("2.5x", speedLabel(2.5f))
        assertEquals("3x", speedLabel(3.0f))
        assertEquals("1.1x", speedLabel(1.1f))
        assertEquals("0.33x", speedLabel(0.333f))
    }

    @Test
    fun `sleep label shows the countdown - the chapter wording or the default`() {
        assertEquals("Sleep timer", sleepLabel(SleepTimer.Off, null))
        assertEquals("Sleep timer", sleepLabel(SleepTimer.Off, 5_000))
        assertEquals("14:59", sleepLabel(SleepTimer.After(15), 899_000))
        assertEquals("15:00", sleepLabel(SleepTimer.After(15), 900_000))
        assertEquals("0:00", sleepLabel(SleepTimer.After(15), 0))
        assertEquals("1:00:00", sleepLabel(SleepTimer.After(60), 3_600_000))
        assertEquals("1:01:01", sleepLabel(SleepTimer.After(60), 3_661_000))
        assertEquals("15 min", sleepLabel(SleepTimer.After(15), null))
        assertEquals("End of chapter", sleepLabel(SleepTimer.EndOfChapter, 30_000))
        assertEquals("End of chapter", sleepLabel(SleepTimer.EndOfChapter, null))
    }

    @Test
    fun `sleep presets and their labels`() {
        assertEquals(
            listOf(SleepTimer.Off, SleepTimer.After(15), SleepTimer.After(30), SleepTimer.After(45), SleepTimer.After(60), SleepTimer.EndOfChapter),
            SLEEP_PRESETS,
        )
        assertEquals("Off", sleepPresetLabel(SleepTimer.Off))
        assertEquals("15 minutes", sleepPresetLabel(SleepTimer.After(15)))
        assertEquals("45 minutes", sleepPresetLabel(SleepTimer.After(45)))
        assertEquals("1 hour", sleepPresetLabel(SleepTimer.After(60)))
        assertEquals("2 hours", sleepPresetLabel(SleepTimer.After(120)))
        assertEquals("90 minutes", sleepPresetLabel(SleepTimer.After(90)))
        assertEquals("1 minute", sleepPresetLabel(SleepTimer.After(1)))
        assertEquals("End of chapter", sleepPresetLabel(SleepTimer.EndOfChapter))
    }

    // ---- chapter navigation ----

    @Test
    fun `previous chapter restarts the current one after three seconds - otherwise goes back`() {
        assertEquals(10_000, previousChapterStart(chapters, 20_000))
        assertEquals(0, previousChapterStart(chapters, 11_000))
        assertEquals(0, previousChapterStart(chapters, 13_000))
        assertEquals(10_000, previousChapterStart(chapters, 13_001))
    }

    @Test
    fun `previous chapter on the first chapter restarts it and an empty list gives zero`() {
        assertEquals(0, previousChapterStart(chapters, 5_000))
        assertEquals(0, previousChapterStart(chapters, 100))
        assertEquals(0, previousChapterStart(emptyList(), 5_000))
    }

    @Test
    fun `previous chapter past the end uses the last chapter`() {
        assertEquals(40_000, previousChapterStart(chapters, 150_000))
    }

    @Test
    fun `next chapter is the start of the following chapter - null on the last or without chapters`() {
        assertEquals(10_000, nextChapterStart(chapters, 5_000))
        assertEquals(40_000, nextChapterStart(chapters, 25_000))
        assertNull(nextChapterStart(chapters, 50_000))
        assertNull(nextChapterStart(emptyList(), 5_000))
        assertEquals(10_000, nextChapterStart(chapters, -5))
    }
}
