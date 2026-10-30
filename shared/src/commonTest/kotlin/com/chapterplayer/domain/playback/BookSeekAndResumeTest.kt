package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookSeekAndResumeTest {

    private val files = listOf(TrackSpan(0, 0, 100_000), TrackSpan(1, 100_000, 100_000), TrackSpan(2, 200_000, 50_000))

    @Test
    fun `back 15 s five seconds into a file lands in the previous file`() {
        assertEquals(TrackPosition(0, 90_000), seekByTarget(files, 1, 5_000, -15_000))
    }

    @Test
    fun `forward 30 s near the end of a file lands in the next file`() {
        assertEquals(TrackPosition(1, 20_000), seekByTarget(files, 0, 90_000, 30_000))
    }

    @Test
    fun `inside one file it stays in that file`() {
        assertEquals(TrackPosition(1, 60_000), seekByTarget(files, 1, 45_000, 15_000))
    }

    @Test
    fun `it clamps to the start and the end of the book`() {
        assertEquals(TrackPosition(0, 0), seekByTarget(files, 0, 5_000, -15_000))
        assertEquals(TrackPosition(2, 50_000), seekByTarget(files, 2, 40_000, 30_000))
    }

    @Test
    fun `no tracks gives null`() {
        assertNull(seekByTarget(emptyList(), 0, 0, 1_000))
    }

    @Test
    fun `no pause means no rewind`() {
        assertNull(ResumeRewind().resumed(60_000, 5_000_000))
    }

    @Test
    fun `a long pause rewinds once - then the pause is forgotten`() {
        val r = ResumeRewind()
        r.paused(1_000)
        assertEquals(50_000L, r.resumed(60_000, 1_000 + 400_000)) // 400 s paused: 10 s back
        assertNull(r.resumed(60_000, 9_000_000))
    }

    @Test
    fun `a short pause does not seek`() {
        val r = ResumeRewind()
        r.paused(1_000)
        assertNull(r.resumed(60_000, 1_000 + 9_999))
    }

    @Test
    fun `the first pause time is kept and a new playlist forgets it`() {
        val r = ResumeRewind()
        r.paused(0)
        r.paused(4_000_000) // still paused; does not move the start
        assertEquals(40_000L, r.resumed(60_000, 4_000_000)) // 4000 s: 20 s back
        r.paused(0)
        r.reset()
        assertNull(r.resumed(60_000, 9_000_000))
    }

    @Test
    fun `a rewind never goes below zero`() {
        val r = ResumeRewind()
        r.paused(0)
        assertEquals(0L, r.resumed(3_000, 400_000))
    }

    @Test
    fun `fixed end remaining rounds up - halves at double speed and is never negative`() {
        assertEquals(1_000L, sleepRemainingToMs(10_000, 9_000, 1f))
        assertEquals(500L, sleepRemainingToMs(10_000, 9_000, 2f))
        assertEquals(1L, sleepRemainingToMs(10_000, 9_999, 1f))
        assertEquals(0L, sleepRemainingToMs(10_000, 10_000, 1f))
        assertEquals(0L, sleepRemainingToMs(10_000, 12_000, 1f))
    }

    @Test
    fun `end of chapter fires exactly once when ticks step across the boundary`() {
        val chapters = listOf(
            com.chapterplayer.data.model.Chapter(0, "A", 0, 600_000),
            com.chapterplayer.data.model.Chapter(1, "B", 600_000, 1_800_000),
        )
        val position0 = 590_100L
        val target = chapters[com.chapterplayer.domain.browse.currentChapterIndex(chapters, position0)!!].endMs
        var armed = true
        var fired = 0
        var position = position0
        repeat(100) {
            position += 400
            if (armed && sleepRemainingToMs(target, position, 1f) == 0L) {
                fired++
                armed = false
            }
        }
        assertEquals(1, fired)
        // The old form never reads zero for these ticks: it jumps to the whole next chapter.
        assertEquals(true, sleepRemainingMs(SleepTimer.EndOfChapter, 0, 600_300, chapters, 1f)!! > 0)
    }

    @Test
    fun `a seek while paused forgets the pause so there is no rewind into the previous chapter`() {
        val r = ResumeRewind()
        r.paused(0)
        r.seeked()
        assertNull(r.resumed(60_000, 60_000))
        // A later pause counts again.
        r.paused(100_000)
        assertEquals(55_000L, r.resumed(60_000, 100_000 + 60_000))
    }
}
