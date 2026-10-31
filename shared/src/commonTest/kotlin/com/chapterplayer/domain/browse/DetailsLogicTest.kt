package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.SeriesRef
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailsLogicTest {

    private fun ch(id: Int, start: Long, end: Long) = Chapter(id = id, title = "Chapter $id", startMs = start, endMs = end)

    private val three = listOf(ch(1, 0, 1_000), ch(2, 1_000, 3_500), ch(3, 3_500, 4_000))

    private fun progress(positionMs: Long, finished: Boolean = false) =
        ReadingProgress("b", positionMs, 4_000, 0, finished)

    private fun book(
        author: String = "Ann Writer",
        narrator: String = "Nora Voice",
        series: String? = null,
        sequence: String? = null,
    ) = AudioBook(
        id = "b", title = "T", author = author, narrator = narrator, durationMs = 4_000,
        chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
        seriesName = series, seriesSequence = sequence,
    )

    // ---- chapterRows ----

    @Test
    fun `no chapters gives no rows`() {
        assertEquals(emptyList(), chapterRows(emptyList(), progress(0)))
    }

    @Test
    fun `rows carry the zero based index - title - start and duration`() {
        val rows = chapterRows(three, null)
        assertEquals(listOf(0, 1, 2), rows.map { it.index })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), rows.map { it.title })
        assertEquals(listOf(0L, 1_000L, 3_500L), rows.map { it.startMs })
        assertEquals(listOf(1_000L, 2_500L, 500L), rows.map { it.durationMs })
    }

    @Test
    fun `without progress the first chapter is current`() {
        assertEquals(listOf(true, false, false), chapterRows(three, null).map { it.isCurrent })
    }

    @Test
    fun `the chapter holding the saved position is the only current one`() {
        assertEquals(listOf(false, true, false), chapterRows(three, progress(1_000)).map { it.isCurrent })
        assertEquals(listOf(false, true, false), chapterRows(three, progress(3_499)).map { it.isCurrent })
        assertEquals(listOf(false, false, true), chapterRows(three, progress(3_500)).map { it.isCurrent })
    }

    @Test
    fun `a finished book has the last chapter current whatever the position`() {
        assertEquals(listOf(false, false, true), chapterRows(three, progress(0, finished = true)).map { it.isCurrent })
    }

    @Test
    fun `a position past the end selects the last chapter`() {
        assertEquals(listOf(false, false, true), chapterRows(three, progress(99_000)).map { it.isCurrent })
    }

    // ---- aboutInfo ----

    @Test
    fun `without live details the info comes from the book and needs a connection`() {
        val info = aboutInfo(book(series = "Fixture Saga", sequence = "2"), null)
        assertTrue(info.needsConnection)
        assertNull(info.description)
        assertEquals(emptyList(), info.genres)
        assertNull(info.year)
        assertEquals("Fixture Saga #2", info.seriesLine)
        assertEquals("By Ann Writer", info.authorLine)
        assertEquals("Narrated by Nora Voice", info.narratorLine)
    }

    @Test
    fun `a series without a sequence reads as just its name and no series gives no line`() {
        assertEquals("Saga", aboutInfo(book(series = "Saga", sequence = null), null).seriesLine)
        assertEquals("Saga", aboutInfo(book(series = " Saga ", sequence = " "), null).seriesLine)
        assertNull(aboutInfo(book(series = null), null).seriesLine)
        assertNull(aboutInfo(book(series = "  "), null).seriesLine)
    }

    @Test
    fun `blank author or narrator gives no line`() {
        val info = aboutInfo(book(author = "", narrator = "  "), null)
        assertNull(info.authorLine)
        assertNull(info.narratorLine)
    }

    @Test
    fun `live details fill description genres year and series and clear the connection note`() {
        val details = BookDetails(
            chapters = emptyList(),
            description = "An invented story.",
            genres = listOf("Fantasy", "Adventure"),
            publishedYear = "2019",
            series = listOf(SeriesRef("Fixture Saga", "2"), SeriesRef("Side Saga", null)),
        )
        val info = aboutInfo(book(series = "Old Name", sequence = "9"), details)
        assertFalse(info.needsConnection)
        assertEquals("An invented story.", info.description)
        assertEquals(listOf("Fantasy", "Adventure"), info.genres)
        assertEquals("2019", info.year)
        assertEquals("Fixture Saga #2, Side Saga", info.seriesLine)
    }

    @Test
    fun `live details without series fall back to the book's series`() {
        val details = BookDetails(emptyList(), null, emptyList(), null, emptyList())
        val info = aboutInfo(book(series = "Fixture Saga", sequence = "2"), details)
        assertFalse(info.needsConnection)
        assertEquals("Fixture Saga #2", info.seriesLine)
    }
}
