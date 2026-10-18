package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesNeighboursTest {

    private fun book(
        id: String,
        title: String = "Title $id",
        series: String? = null,
        sequence: String? = null,
        serverId: String = "s1",
    ) = AudioBook(
        id = id, title = title, author = "Author", narrator = "Narrator", durationMs = 1_000L,
        chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = serverId,
        seriesName = series, seriesSequence = sequence,
    )

    private fun item(b: AudioBook) = BrowseItem(b, progress = null, downloadState = DownloadState.NOT_DOWNLOADED)

    private fun ids(entries: List<SeriesEntry>) = entries.map { it.item.book.id }

    @Test
    fun `a book without a series or with a blank series has no neighbours`() {
        val none = book("a", series = null)
        val blank = book("b", series = "   ")
        assertEquals(emptyList(), seriesNeighbours(listOf(item(none), item(blank)), none))
        assertEquals(emptyList(), seriesNeighbours(listOf(item(none), item(blank)), blank))
    }

    @Test
    fun `a series with only the current book gives one current entry`() {
        val only = book("a", series = "Saga", sequence = "1")
        val result = seriesNeighbours(listOf(item(only), item(book("z", series = "Other"))), only)
        assertEquals(listOf("a"), ids(result))
        assertEquals(listOf(true), result.map { it.isCurrent })
    }

    @Test
    fun `books are ordered by numeric sequence with missing sequences last`() {
        val current = book("two", series = "Saga", sequence = "2")
        val all = listOf(
            item(book("ten", series = "Saga", sequence = "10")),
            item(book("none", series = "Saga", sequence = null)),
            item(current),
            item(book("threeHalf", series = "Saga", sequence = "3.5")),
            item(book("one", series = "Saga", sequence = "1")),
        )
        assertEquals(listOf("one", "two", "threeHalf", "ten", "none"), ids(seriesNeighbours(all, current)))
    }

    @Test
    fun `only the current book is flagged`() {
        val current = book("b", series = "Saga", sequence = "2")
        val all = listOf(item(book("a", series = "Saga", sequence = "1")), item(current), item(book("c", series = "Saga", sequence = "3")))
        val result = seriesNeighbours(all, current)
        assertEquals(listOf(false, true, false), result.map { it.isCurrent })
    }

    @Test
    fun `the series name matches ignoring case and surrounding spaces`() {
        val current = book("a", series = "Saga", sequence = "1")
        val all = listOf(
            item(current),
            item(book("b", series = "  saga ", sequence = "2")),
            item(book("c", series = "SAGA", sequence = "3")),
            item(book("d", series = "Saga Two", sequence = "4")),
        )
        assertEquals(listOf("a", "b", "c"), ids(seriesNeighbours(all, current)))
    }

    @Test
    fun `books from another server with the same series name are left out`() {
        val current = book("a", series = "Saga", sequence = "1", serverId = "s1")
        val all = listOf(
            item(current),
            item(book("b", series = "Saga", sequence = "2", serverId = "s1")),
            item(book("x", series = "Saga", sequence = "2", serverId = "s2")),
        )
        assertEquals(listOf("a", "b"), ids(seriesNeighbours(all, current)))
    }

    @Test
    fun `equal sequences are ordered by title then id`() {
        val current = book("b", title = "Beta", series = "Saga", sequence = "1")
        val all = listOf(
            item(current),
            item(book("a2", title = "Alpha", series = "Saga", sequence = "1")),
            item(book("a1", title = "Alpha", series = "Saga", sequence = "1")),
        )
        assertEquals(listOf("a1", "a2", "b"), ids(seriesNeighbours(all, current)))
    }
}
