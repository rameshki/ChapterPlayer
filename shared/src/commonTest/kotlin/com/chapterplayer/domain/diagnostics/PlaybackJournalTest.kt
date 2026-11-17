package com.chapterplayer.domain.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackJournalTest {
    @Test
    fun `durations read as hours minutes seconds without colons`() {
        assertEquals("0m00s", journalDuration(0L))
        assertEquals("4m05s", journalDuration(245_000L))
        assertEquals("1h02m03s", journalDuration(3_723_000L))
        assertEquals("0m00s", journalDuration(-5L))
    }

    @Test
    fun `an event carries the place when it is known`() {
        assertEquals("paused (the person)", journalEvent("paused (the person)", null))
        assertEquals("paused (audio focus lost) at 1h02m03s", journalEvent("paused (audio focus lost)", 3_723_000L))
    }

    @Test
    fun `the journal keeps the newest lines`() {
        val lines = (1..250).map { "line $it" }
        val kept = trimJournal(lines)
        assertEquals(JOURNAL_LINES, kept.size)
        assertEquals("line 51", kept.first())
        assertEquals("line 250", kept.last())
    }

    @Test
    fun `a process exit line names the reason and what the app was doing`() {
        assertEquals(
            "2026-11-16 03h49m killed by a signal (status 9), was a foreground service (playing)",
            describeProcessExit("2026-11-16 03h49m", reason = 2, status = 9, importance = 125),
        )
        assertEquals("t reason 99 (status 0), importance 7", describeProcessExit("t", 99, 0, 7))
    }

    @Test
    fun `a journal line and an exit line survive the report redaction`() {
        val line = "2026-11-16 03h49m12s paused (audio focus lost) at 1h02m03s"
        val exit = describeProcessExit("2026-11-16 03h49m", 2, 9, 125)
        assertEquals(line, redact(line))
        assertEquals(exit, redact(exit))
        assertFalse("[" in redact(line + exit))
    }
}
