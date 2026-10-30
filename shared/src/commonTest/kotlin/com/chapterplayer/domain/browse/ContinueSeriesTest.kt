package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ContinueSeriesTest {

    private fun item(
        id: String,
        series: String? = "Saga",
        sequence: String? = null,
        positionMs: Long? = null,
        finished: Boolean = false,
        lastPlayedAt: Long = 0,
        serverId: String = "s1",
    ): BrowseItem {
        val book = AudioBook(
            id = id, title = "Title $id", author = "Author", narrator = "Narrator", durationMs = 1_000_000,
            chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = serverId,
            seriesName = series, seriesSequence = sequence,
        )
        val progress = positionMs?.let { ReadingProgress(id, it, 1_000_000, lastPlayedAt, finished) }
        return BrowseItem(book, progress, DownloadState.NOT_DOWNLOADED)
    }

    private fun ids(items: List<BrowseItem>) = items.map { it.book.id }

    @Test
    fun `after a finished book the next one in the series is offered`() {
        val items = listOf(
            item("b1", sequence = "1", positionMs = 1_000_000, finished = true),
            item("b2", sequence = "2"),
            item("b3", sequence = "3"),
        )
        assertEquals(listOf("b2"), ids(continueSeries(items)))
    }

    @Test
    fun `only one book per series even when several follow`() {
        val items = listOf(
            item("b1", sequence = "1", positionMs = 1, finished = true),
            item("b2", sequence = "2", positionMs = 1, finished = true),
            item("b3", sequence = "3"),
            item("b4", sequence = "4"),
        )
        assertEquals(listOf("b3"), ids(continueSeries(items)))
    }

    @Test
    fun `nothing is offered while the most advanced book is still in progress`() {
        val items = listOf(
            item("b1", sequence = "1", positionMs = 1, finished = true),
            item("b2", sequence = "2", positionMs = 500_000),
            item("b3", sequence = "3"),
        )
        assertEquals(emptyList(), ids(continueSeries(items)))
    }

    @Test
    fun `a series that was never started or has no book after the finished one is left out`() {
        val untouched = listOf(item("b1", sequence = "1"), item("b2", sequence = "2"))
        assertEquals(emptyList(), ids(continueSeries(untouched)))
        val lastDone = listOf(item("b1", sequence = "1", positionMs = 1, finished = true))
        assertEquals(emptyList(), ids(continueSeries(lastDone)))
    }

    @Test
    fun `the next book follows the most advanced touched book - not the first untouched one`() {
        val items = listOf(
            item("b1", sequence = "1", positionMs = 1, finished = true),
            item("b2", sequence = "2"),
            item("b3", sequence = "3", positionMs = 1, finished = true),
        )
        assertEquals(emptyList(), ids(continueSeries(items)))
    }

    @Test
    fun `gaps in the sequence and a missing sequence follow the series order`() {
        val gaps = listOf(
            item("b1", sequence = "1", positionMs = 1, finished = true),
            item("b5", sequence = "5"),
            item("b3", sequence = "3"),
        )
        assertEquals(listOf("b3"), ids(continueSeries(gaps)))
        val missing = listOf(
            item("b1", sequence = "1", positionMs = 1, finished = true),
            item("x", sequence = null),
        )
        assertEquals(listOf("x"), ids(continueSeries(missing)))
    }

    @Test
    fun `series are ordered by how recently one of their books was played`() {
        val items = listOf(
            item("a1", series = "A", sequence = "1", positionMs = 1, finished = true, lastPlayedAt = 100),
            item("a2", series = "A", sequence = "2"),
            item("b1", series = "B", sequence = "1", positionMs = 1, finished = true, lastPlayedAt = 200),
            item("b2", series = "B", sequence = "2"),
        )
        assertEquals(listOf("b2", "a2"), ids(continueSeries(items)))
    }

    @Test
    fun `books from different servers with the same series name are separate series`() {
        val items = listOf(
            item("s1b1", sequence = "1", positionMs = 1, finished = true, serverId = "s1"),
            item("s1b2", sequence = "2", serverId = "s1"),
            item("s2b2", sequence = "2", serverId = "s2"),
        )
        assertEquals(listOf("s1b2"), ids(continueSeries(items)))
    }

    @Test
    fun `books without a series are ignored and one book can be excluded`() {
        val items = listOf(
            item("solo", series = null, positionMs = 1, finished = true),
            item("b1", sequence = "1", positionMs = 1, finished = true),
            item("b2", sequence = "2"),
        )
        assertEquals(listOf("b2"), ids(continueSeries(items)))
        assertEquals(emptyList(), ids(continueSeries(items, excludeBookId = "b2")))
    }
}
