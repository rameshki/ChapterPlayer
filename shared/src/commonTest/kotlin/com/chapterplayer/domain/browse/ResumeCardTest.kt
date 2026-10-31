package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumeCardTest {

    private fun item(
        id: String,
        positionMs: Long? = null,
        lastPlayedAt: Long = 0,
        finished: Boolean = false,
        durationMs: Long = 3_600_000,
        chapters: List<Chapter> = emptyList(),
    ): BrowseItem {
        val book = AudioBook(
            id = id, title = "Title $id", author = "Author", narrator = "Narrator", durationMs = durationMs,
            chapters = chapters, streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
        )
        val progress = positionMs?.let { ReadingProgress(id, it, durationMs, lastPlayedAt, finished) }
        return BrowseItem(book, progress, DownloadState.NOT_DOWNLOADED)
    }

    @Test
    fun `no items or no progress gives no candidate`() {
        assertNull(resumeCandidate(emptyList()))
        assertNull(resumeCandidate(listOf(item("a"), item("b"))))
    }

    @Test
    fun `finished books and a position of zero are not candidates`() {
        assertNull(resumeCandidate(listOf(item("a", positionMs = 1_000, finished = true), item("b", positionMs = 0))))
    }

    @Test
    fun `the most recently played unfinished book wins and ties go to the smaller id`() {
        val items = listOf(
            item("a", positionMs = 1_000, lastPlayedAt = 100),
            item("b", positionMs = 1_000, lastPlayedAt = 300),
            item("c", positionMs = 1_000, lastPlayedAt = 200),
        )
        assertEquals("b", resumeCandidate(items)!!.item.book.id)
        val tie = listOf(item("z", positionMs = 1_000, lastPlayedAt = 50), item("m", positionMs = 1_000, lastPlayedAt = 50))
        assertEquals("m", resumeCandidate(tie)!!.item.book.id)
    }

    @Test
    fun `a finished book that was played more recently does not hide an unfinished one`() {
        val items = listOf(
            item("old", positionMs = 5_000, lastPlayedAt = 100),
            item("done", positionMs = 5_000, lastPlayedAt = 900, finished = true),
        )
        assertEquals("old", resumeCandidate(items)!!.item.book.id)
    }

    @Test
    fun `the card carries the chapter title - the fraction and the time left`() {
        val chapters = listOf(Chapter(1, "Opening", 0, 600_000), Chapter(2, "Middle", 600_000, 3_600_000))
        val info = resumeCandidate(listOf(item("a", positionMs = 900_000, chapters = chapters)))!!
        assertEquals("Middle", info.chapterTitle)
        assertEquals(0.25f, info.fraction)
        assertEquals("45 min left", info.timeLeft)
    }

    @Test
    fun `a book without chapters has no chapter title`() {
        val info = resumeCandidate(listOf(item("a", positionMs = 900_000)))!!
        assertNull(info.chapterTitle)
        assertEquals(0.25f, info.fraction)
    }

    @Test
    fun `a book left at its end without the finished flag is not a candidate`() {
        // Within the finished window of the end (see isFinishedAt) but not flagged.
        assertNull(resumeCandidate(listOf(item("a", positionMs = 3_599_000, durationMs = 3_600_000))))
        assertEquals("b", resumeCandidate(listOf(item("a", positionMs = 3_599_000, lastPlayedAt = 9), item("b", positionMs = 1_000, lastPlayedAt = 1)))!!.item.book.id)
    }
}
