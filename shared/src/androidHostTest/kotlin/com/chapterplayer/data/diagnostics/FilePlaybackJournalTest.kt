package com.chapterplayer.data.diagnostics

import com.chapterplayer.domain.diagnostics.JOURNAL_LINES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePlaybackJournalTest {
    private fun tempFile(): File = File.createTempFile("journal", ".txt").also { it.delete() }

    @Test
    fun `a line has a time, the event and the place, and survives a restart`() {
        val file = tempFile()
        FilePlaybackJournal(file, stamp = { "2026-11-16 03h49m12s" }).add("paused (audio focus lost)", 3_723_000L)
        val back = FilePlaybackJournal(file).load()
        assertEquals(listOf("2026-11-16 03h49m12s paused (audio focus lost) at 1h02m03s"), back)
        file.delete()
    }

    @Test
    fun `nothing kept yet is an empty journal`() {
        assertEquals(emptyList(), FilePlaybackJournal(tempFile()).load())
    }

    @Test
    fun `the file is cut to the newest lines once it has doubled`() {
        val file = tempFile()
        val journal = FilePlaybackJournal(file, stamp = { "t" })
        repeat(JOURNAL_LINES * 2 + 5) { journal.add("event $it") }
        val kept = journal.load()
        assertTrue(kept.size <= JOURNAL_LINES)
        assertEquals("t event ${JOURNAL_LINES * 2 + 4}", kept.last())
        assertTrue(file.readLines().size <= JOURNAL_LINES * 2)
        file.delete()
    }

    @Test
    fun `an event cannot add a line or carry a secret`() {
        val file = tempFile()
        val journal = FilePlaybackJournal(file, stamp = { "t" })
        journal.add("error\nforged line token=abc123SECRET at https://books.example.org/x")
        val back = journal.load()
        assertEquals(1, back.size)
        assertFalse("abc123SECRET" in back.single())
        assertFalse("books.example.org" in back.single())
        file.delete()
    }

    @Test
    fun `an unwritable place does not throw`() {
        val journal = FilePlaybackJournal(File("Z:/no/such/folder/journal.txt"), stamp = { "t" })
        journal.add("event")
        assertEquals(emptyList(), journal.load())
    }
}
