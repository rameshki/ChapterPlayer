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
    fun `a finished book offers mark as not finished - anything else offers mark as finished`() {
        assertEquals(MarkAction.MarkNotFinished, markActionFor(progress(100_000, 1, finished = true)))
        assertEquals(MarkAction.MarkFinished, markActionFor(progress(10_000, 1)))
        assertEquals(MarkAction.MarkFinished, markActionFor(null))
    }

    // ---- local result ----

    @Test
    fun `finished starts over at zero - sets the flag and the time`() {
        val after = progressAfterMark(progress(40_000, 5), "a", finished = true, durationMs = 1L, nowMs = 900)
        assertEquals(ReadingProgress("a", 0, 100_000, 900, isFinished = true), after)
    }

    // ---- what the phone keeps from the server's answer ----

    @Test
    fun `a finished answer keeps the mark at zero whatever place the server kept`() {
        val mark = ReadingProgress("a", 0, 100_000, 900, isFinished = true)
        // Audiobookshelf keeps the old place and Plex answers with the end: neither brings it back
        assertEquals(mark, progressAfterServerAnswer(mark, ServerProgress(40_000, 100_000, true, 950)))
        assertEquals(mark, progressAfterServerAnswer(mark, ServerProgress(100_000, 100_000, true, 950)))
    }

    @Test
    fun `an answer that is not finished is what the server says with the time of the mark`() {
        val mark = ReadingProgress("a", 0, 100_000, 900, isFinished = true)
        assertEquals(ReadingProgress("a", 7_000, 100_000, 900, false), progressAfterServerAnswer(mark, ServerProgress(7_000, 0, false, 950)))
        val unmark = ReadingProgress("a", 0, 100_000, 900, isFinished = false)
        assertEquals(ReadingProgress("a", 0, 120_000, 900, false), progressAfterServerAnswer(unmark, ServerProgress(0, 120_000, false, 950)))
    }

    // ---- the player's place from before the rewind ----

    @Test
    fun `a save away from the start soon after the rewind is the old place`() {
        assertTrue(isSaveBeforeRewind(positionMs = 40_000, nowMs = 1_000, rewoundAtMs = 1_000))
        assertTrue(isSaveBeforeRewind(positionMs = 40_000, nowMs = 1_000 + MARK_REWIND_GRACE_MS, rewoundAtMs = 1_000))
    }

    @Test
    fun `a pinned save is the old place then the start then listening again`() {
        assertEquals(PinnedSave.OLD_PLACE, pinnedSave(positionMs = 40_000, nowMs = 1_200, rewoundAtMs = 1_000))
        assertEquals(PinnedSave.KEEP, pinnedSave(positionMs = 500, nowMs = 1_200, rewoundAtMs = 1_000))
        assertEquals(PinnedSave.KEEP, pinnedSave(positionMs = MARK_REWIND_SEEN_MS, nowMs = 60_000, rewoundAtMs = null))
        assertEquals(PinnedSave.LISTENING_AGAIN, pinnedSave(positionMs = 10_000, nowMs = 1_200, rewoundAtMs = null))
        assertEquals(PinnedSave.LISTENING_AGAIN, pinnedSave(positionMs = 40_000, nowMs = 7_000, rewoundAtMs = 1_000))
    }

    @Test
    fun `a finished book saved as not finished was played again`() {
        assertTrue(playedAgain(progress(0, at = 5, finished = true), progress(10_000, at = 9)))
        assertTrue(playedAgain(progress(100_000, at = 5, finished = true), progress(10_000, at = 9)))
        assertFalse(playedAgain(progress(0, at = 5, finished = true), progress(0, at = 9, finished = true)))
        assertFalse(playedAgain(progress(5_000, at = 5), progress(10_000, at = 9)))
        assertFalse(playedAgain(null, progress(10_000, at = 9)))
    }

    @Test
    fun `a save at the very start is no listening again and keeps the book finished`() {
        val finished = progress(100_000, at = 5, finished = true)
        assertFalse(playedAgain(finished, progress(MARK_REWIND_SEEN_MS, at = 9)))
        assertTrue(staysFinishedAtStart(finished, progress(1_000, at = 9)))
        assertFalse(staysFinishedAtStart(finished, progress(MARK_REWIND_SEEN_MS + 1, at = 9)))
        assertFalse(staysFinishedAtStart(progress(5_000, at = 5), progress(1_000, at = 9)))
    }

    @Test
    fun `after a failed rewind nothing past the start ends the mark`() {
        assertEquals(PinnedSave.OLD_PLACE, pinnedSave(positionMs = 40_000, nowMs = 60_000, rewoundAtMs = null, rewindFailed = true))
        assertEquals(PinnedSave.KEEP, pinnedSave(positionMs = 1_000, nowMs = 60_000, rewoundAtMs = null, rewindFailed = true))
        assertEquals(MarkedReport.SKIP, reportAfterMark(positionMs = 40_000, nowMs = 60_000, markedAtMs = 1_000, isPlaying = true, rewindFailed = true))
    }

    @Test
    fun `a queued not finished yields to a newer place at the end`() {
        val end = coalescePending(pending(10_000, 10, marked = false), pending(99_000, 20))
        assertEquals(null, end.markedFinished)
        assertEquals(99_000, end.positionMs)
        // a newer place before the end keeps the not finished
        assertEquals(false, coalescePending(pending(10_000, 10, marked = false), pending(40_000, 20)).markedFinished)
    }

    @Test
    fun `a queued not finished follows newer server wins except for Plex`() {
        val reopen = pending(10_000, 500, marked = false)
        // Audiobookshelf or Jellyfin: a newer finish from another device stays
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(reopen, ServerProgress(100_000, 100_000, true, 900)))
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(reopen, ServerProgress(20_000, 100_000, false, 900)))
        assertEquals(FlushDecision.SEND, flushDecision(reopen, ServerProgress(100_000, 100_000, true, 400)))
        // Plex: an album kept all scrobbled reads as newer while it is heard again
        val plex = reopen.copy(bookId = "plex_10")
        assertEquals(FlushDecision.SEND, flushDecision(plex, ServerProgress(100_000, 100_000, true, 900)))
    }

    @Test
    fun `a half applied reopen at zero on the server is sent again`() {
        val reopen = pending(10_000, 500, marked = false)
        assertEquals(FlushDecision.SEND, flushDecision(reopen, ServerProgress(0, 100_000, false, 900)))
        // an ordinary position or a not finished at zero follow newer server wins
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(pending(10_000, 500), ServerProgress(0, 100_000, false, 900)))
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(pending(0, 500, marked = false), ServerProgress(0, 100_000, false, 900)))
    }

    @Test
    fun `a half applied finish at zero on the server is sent again`() {
        // Mark as finished is two patches on Audiobookshelf: the first leaves the server not finished at 0 with a time newer
        // than the queued mark, which must not read as another device having the last word.
        val mark = pending(0, 500, marked = true)
        assertEquals(FlushDecision.SEND, flushDecision(mark, ServerProgress(0, 100_000, false, 900)))
        // a server that is not at zero, a mark that carries a place, and a finished server still follow newer server wins
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(mark, ServerProgress(20_000, 100_000, false, 900)))
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(pending(5_000, 500, marked = true), ServerProgress(0, 100_000, false, 900)))
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(mark, ServerProgress(0, 100_000, true, 900)))
    }

    @Test
    fun `a half applied finish on Plex is sent again`() {
        // Plex scrobbles every track: after some of them the album is not finished yet and its time is newer than the mark.
        val mark = pending(0, 500, marked = true).copy(bookId = "plex_10")
        assertEquals(FlushDecision.SEND, flushDecision(mark, ServerProgress(40_000, 100_000, false, 900)))
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(mark, ServerProgress(100_000, 100_000, true, 900)))
    }

    @Test
    fun `a failed rewind holds the mark only for a while`() {
        assertTrue(rewindStillFailed(failedAtMs = 1_000, nowMs = 1_000 + MARK_REWIND_FAILED_LIMIT_MS))
        assertFalse(rewindStillFailed(failedAtMs = 1_000, nowMs = 1_001 + MARK_REWIND_FAILED_LIMIT_MS))
        assertFalse(rewindStillFailed(failedAtMs = null, nowMs = 1_000))
    }

    @Test
    fun `a finished book touched only at the start tells no server`() {
        assertTrue(silentAtStart(progress(100_000, at = 5, finished = true), MARK_REWIND_SEEN_MS))
        assertFalse(silentAtStart(progress(100_000, at = 5, finished = true), MARK_REWIND_SEEN_MS + 1))
        assertFalse(silentAtStart(progress(40_000, at = 5), 1_000))
        assertFalse(silentAtStart(null, 1_000))
    }

    @Test
    fun `after a mark the old place and the pause at the start are held back`() {
        assertEquals(MarkedReport.SKIP, reportAfterMark(positionMs = 40_000, nowMs = 1_200, markedAtMs = 1_000, isPlaying = false))
        // a tick that read the player before its pause
        assertEquals(MarkedReport.SKIP, reportAfterMark(positionMs = 40_000, nowMs = 1_200, markedAtMs = 1_000, isPlaying = true))
        assertEquals(MarkedReport.SKIP, reportAfterMark(positionMs = 500, nowMs = 1_200, markedAtMs = 1_000, isPlaying = false))
        assertEquals(MarkedReport.SKIP, reportAfterMark(positionMs = 500, nowMs = 60_000, markedAtMs = 1_000, isPlaying = false))
    }

    @Test
    fun `after a mark listening again is sent`() {
        assertEquals(MarkedReport.SEND_AND_RESUME, reportAfterMark(positionMs = 10_000, nowMs = 12_000, markedAtMs = 1_000, isPlaying = true))
        assertEquals(MarkedReport.SEND_AND_RESUME, reportAfterMark(positionMs = 500, nowMs = 1_500, markedAtMs = 1_000, isPlaying = true))
        assertEquals(MarkedReport.SEND_AND_RESUME, reportAfterMark(positionMs = 8_000, nowMs = 9_000, markedAtMs = 1_000, isPlaying = false))
    }

    @Test
    fun `a save near the start or later or without a rewind is the real place`() {
        assertFalse(isSaveBeforeRewind(positionMs = MARK_REWIND_SEEN_MS, nowMs = 1_000, rewoundAtMs = 1_000))
        assertFalse(isSaveBeforeRewind(positionMs = 40_000, nowMs = 1_001 + MARK_REWIND_GRACE_MS, rewoundAtMs = 1_000))
        assertFalse(isSaveBeforeRewind(positionMs = 40_000, nowMs = 900, rewoundAtMs = 1_000))
        assertFalse(isSaveBeforeRewind(positionMs = 40_000, nowMs = 1_000, rewoundAtMs = null))
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
        // a finished mark keeps its place: the later position is the player's place before the rewind or listening again
        assertEquals(5_000, merged.positionMs)
        assertEquals(20, merged.updatedAtMs)
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
    fun `a not finished mark takes a newer ordinary position as always`() {
        val merged = coalescePending(pending(0, 10, marked = false), pending(7_000, 20))
        assertEquals(7_000, merged.positionMs)
        assertEquals(false, merged.markedFinished)
    }

    @Test
    fun `a finished mark at zero queued before the old player place still sends zero`() {
        val merged = coalescePending(pending(0, 10, marked = true), pending(40_000, 11))
        assertEquals(0, merged.positionMs)
        assertEquals(true, merged.markedFinished)
    }

    @Test
    fun `of two marks the newer wins`() {
        assertEquals(false, coalescePending(pending(1, 10, marked = true), pending(1, 20, marked = false)).markedFinished)
        assertEquals(true, coalescePending(pending(1, 20, marked = true), pending(1, 10, marked = false)).markedFinished)
    }

    @Test
    fun `a newer server beats a queued mark - a mark is newer than an older server`() {
        val mark = pending(5_000, 500, marked = true)
        assertEquals(FlushDecision.SEND, flushDecision(mark, ServerProgress(1, 100_000, false, 400)))
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(mark, ServerProgress(1, 100_000, false, 600)))
    }
}
