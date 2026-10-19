package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerEngineTest {

    private val chapters = listOf(
        Chapter(0, "A", 0, 600_000),
        Chapter(1, "B", 600_000, 1_200_000),
        Chapter(2, "C", 1_200_000, 1_800_000),
    )

    private fun engine(position: Long): SleepTimerEngine =
        SleepTimerEngine().also { it.set(SleepTimer.EndOfChapter, position, chapters) }

    @Test
    fun `ticks stepping across the chapter end fire exactly once and leave the timer off`() {
        val e = engine(590_100)
        var position = 590_100L
        var fired = 0
        repeat(100) {
            position += 400
            if (e.tick(position, chapters, 1f)) fired++
        }
        assertEquals(1, fired)
        assertEquals(SleepTimer.Off, e.timer)
        assertNull(e.remainingMs(position, chapters, 1f))
    }

    @Test
    fun `a tick landing exactly on the end fires`() {
        val e = engine(599_000)
        assertFalse(e.tick(599_999, chapters, 1f))
        assertTrue(e.tick(600_000, chapters, 1f))
    }

    @Test
    fun `seeking back re-aims at the earlier chapter, forward at the later one`() {
        val e = engine(1_000_000) // chapter B, target 1_200_000
        assertEquals(200_000L, e.remainingMs(1_000_000, chapters, 1f))
        e.userSeeked(100_000, chapters) // back into A
        assertEquals(500_000L, e.remainingMs(100_000, chapters, 1f))
        assertTrue(e.tick(1_000_000, chapters, 1f)) // position past the new target: fires
        val f = engine(100_000)
        f.userSeeked(1_300_000, chapters) // forward into C
        assertEquals(500_000L, f.remainingMs(1_300_000, chapters, 1f))
        assertFalse(f.tick(1_300_000, chapters, 1f))
    }

    @Test
    fun `a seek does not fire the timer by itself and never moves an after timer`() {
        val e = SleepTimerEngine()
        e.set(SleepTimer.After(5), 0, chapters)
        e.addPlayed(60_000)
        e.userSeeked(900_000, chapters)
        assertEquals(240_000L, e.remainingMs(900_000, chapters, 1f))
        assertEquals(SleepTimer.After(5), e.timer)
    }

    @Test
    fun `set while paused aims at the chapter of that moment and waits for playing`() {
        val e = engine(300_000) // paused here
        assertFalse(e.tick(300_000, chapters, 1f)) // ticks while paused change nothing
        assertEquals(300_000L, e.remainingMs(300_000, chapters, 1f))
        assertTrue(e.tick(600_100, chapters, 1f))
    }

    @Test
    fun `an after timer counts played time and fires once`() {
        val e = SleepTimerEngine()
        e.set(SleepTimer.After(1), 0, chapters)
        e.addPlayed(59_999)
        assertFalse(e.tick(0, chapters, 1f))
        e.addPlayed(1)
        assertTrue(e.tick(0, chapters, 1f))
        assertFalse(e.tick(0, chapters, 1f))
    }

    @Test
    fun `a book change resets the timer`() {
        val e = engine(590_000)
        e.reset()
        assertEquals(SleepTimer.Off, e.timer)
        assertFalse(e.tick(700_000, chapters, 1f))
    }

    @Test
    fun `end of chapter in a book without chapters never fires`() {
        val e = SleepTimerEngine()
        e.set(SleepTimer.EndOfChapter, 10, emptyList())
        assertNull(e.remainingMs(10, emptyList(), 1f))
        assertFalse(e.tick(10, emptyList(), 1f))
    }

    @Test
    fun `speed shortens the wall clock to the chapter end`() {
        assertEquals(5_000L, engine(590_000).remainingMs(590_000, chapters, 2f))
    }
}
