package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkFinishedTest {

    private fun progress(pos: Long, at: Long, finished: Boolean = false, duration: Long = 100_000L) =
        ReadingProgress("a", pos, duration, at, finished)

    private fun pending(pos: Long, at: Long, marked: Boolean? = null, listened: Long = 0L) =
        PendingProgress("a", "s", pos, 100_000L, listened, at, markedFinished = marked)

    // ---- what the menu offers ----

    @Test
    fun `a finished book offers mark as not finished, anything else offers mark as finished`() {
        assertEquals(MarkAction.MarkNotFinished, markActionFor(progress(100_000, 1, finished = true)))
        assertEquals(MarkAction.MarkFinished, markActionFor(progress(10_000, 1)))
        assertEquals(MarkAction.MarkFinished, markActionFor(null))
    }

    // ---- local result ----

    @Test
    fun `finished keeps the position, sets the flag and the time`() {
        val after = progressAfterMark(progress(40_000, 5), "a", finished = true, durationMs = 1L, nowMs = 900)
        assertEquals(ReadingProgress("a", 40_000, 100_000, 900, isFinished = true), after)
    }

    @Test
    fun `not finished starts over at zero`() {
        val after = progressAfterMark(progress(100_000, 5, finished = true), "a", finished = false, durationMs = 1L, nowMs = 900)
        assertEquals(ReadingProgress("a", 0, 100_000, 900, isFinished = false), after)
    }

    @Test
    fun `a book without progress gets a row with the book length`() {
        assertEquals(ReadingProgress("a", 0, 55_000, 9, isFinished = true), progressAfterMark(null, "a", true, 55_000, 9))
        assertEquals(ReadingProgress("a", 0, 55_000, 9, isFinished = false), progressAfterMark(null, "a", false, 55_000, 9))
    }

    // ---- the player's own save must not undo a mark ----

    @Test
    fun `a user mark made after the book was loaded is kept`() {
        assertTrue(keepsFinishedMark(progress(10_000, at = 800, finished = true), userMarkAt = 800, loadedSinceMs = 700))
    }

    @Test
    fun `nothing else is kept`() {
        // a finished row merged from the server: no user mark
        assertFalse(keepsFinishedMark(progress(10_000, at = 800, finished = true), userMarkAt = null, loadedSinceMs = 700))
        // the row was rewritten after the mark (not finished, or another time)
        assertFalse(keepsFinishedMark(progress(0, at = 900), userMarkAt = 800, loadedSinceMs = 700))
        assertFalse(keepsFinishedMark(progress(10_000, at = 900, finished = true), userMarkAt = 800, loadedSinceMs = 700))
        // the mark was made before this load
        assertFalse(keepsFinishedMark(progress(10_000, at = 600, finished = true), userMarkAt = 600, loadedSinceMs = 700))
        assertFalse(keepsFinishedMark(null, userMarkAt = 800, loadedSinceMs = 700))
    }

    // ---- queue: a mark is not lost under later position rows ----

    @Test
    fun `an explicit mark survives a newer ordinary position`() {
        val merged = coalescePending(pending(5_000, 10, marked = true), pending(7_000, 20, listened = 3_000))
        assertEquals(7_000, merged.positionMs)
        assertEquals(true, merged.markedFinished)
        assertEquals(3_000, merged.listenedMs)
    }

    @Test
    fun `an older ordinary position under a newer mark takes the mark`() {
        val merged = coalescePending(pending(7_000, 10), pending(5_000, 20, marked = false))
        assertEquals(false, merged.markedFinished)
        assertEquals(5_000, merged.positionMs)
    }

    @Test
    fun `of two marks the newer wins`() {
        assertEquals(false, coalescePending(pending(1, 10, marked = true), pending(1, 20, marked = false)).markedFinished)
        assertEquals(true, coalescePending(pending(1, 20, marked = true), pending(1, 10, marked = false)).markedFinished)
    }

    @Test
    fun `a newer server beats a queued mark, a mark is newer than an older server`() {
        val mark = pending(5_000, 500, marked = true)
        assertEquals(FlushDecision.SEND, flushDecision(mark, ServerProgress(1, 100_000, false, 400)))
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(mark, ServerProgress(1, 100_000, false, 600)))
    }
}
