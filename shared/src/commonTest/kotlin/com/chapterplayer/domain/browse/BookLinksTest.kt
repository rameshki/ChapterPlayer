package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookLinksTest {

    private fun book(
        id: String,
        author: String = "Author One",
        narrator: String = "Narrator One",
        series: String? = null,
        sequence: String? = null,
    ) = AudioBook(
        id = id, title = "Title $id", author = author, narrator = narrator, durationMs = 1_000L,
        chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
        seriesName = series, seriesSequence = sequence,
    )

    private fun item(b: AudioBook) = BrowseItem(b, progress = null, downloadState = DownloadState.NOT_DOWNLOADED)

    @Test
    fun `a book without a series or with a blank series has no series link`() {
        val none = book("a", series = null)
        val blank = book("b", series = "  ")
        assertNull(bookLinks(listOf(item(none), item(blank)), none).series)
        assertNull(bookLinks(listOf(item(none), item(blank)), blank).series)
    }

    @Test
    fun `the series link carries the group key - the trimmed title - the sequence and the book count`() {
        val current = book("a", series = " The Saga ", sequence = "2")
        val all = listOf(item(current), item(book("b", series = "the saga", sequence = "1")), item(book("c", series = "Other")))
        assertEquals(SeriesLink(key = "the saga", title = "The Saga", sequence = "2", bookCount = 2), bookLinks(all, current).series)
    }

    @Test
    fun `a series link with no sequence keeps it null`() {
        val current = book("a", series = "Saga", sequence = null)
        assertNull(bookLinks(listOf(item(current)), current).series!!.sequence)
    }

    @Test
    fun `an author with other books gets a link counting the others`() {
        val current = book("a", author = "Ann Writer")
        val all = listOf(item(current), item(book("b", author = "Ann Writer")), item(book("c", author = "ann writer")), item(book("d", author = "Someone Else")))
        assertEquals(listOf(PersonLink(key = "ann writer", title = "Ann Writer", otherBookCount = 2)), bookLinks(all, current).authors)
    }

    @Test
    fun `an author with no other books is left out`() {
        val current = book("a", author = "Ann Writer")
        val all = listOf(item(current), item(book("b", author = "Someone Else")))
        assertEquals(emptyList(), bookLinks(all, current).authors)
    }

    @Test
    fun `with two authors only the one with other books is linked and links are ordered by title`() {
        val current = book("a", author = "Zed Zane, Ann Writer")
        val all = listOf(item(current), item(book("b", author = "Zed Zane")), item(book("c", author = "Ann Writer")))
        assertEquals(
            listOf(PersonLink("ann writer", "Ann Writer", 1), PersonLink("zed zane", "Zed Zane", 1)),
            bookLinks(all, current).authors,
        )
        val soloOther = listOf(item(current), item(book("b", author = "Zed Zane")))
        assertEquals(listOf(PersonLink("zed zane", "Zed Zane", 1)), bookLinks(soloOther, current).authors)
    }

    @Test
    fun `an unknown author or narrator is never linked`() {
        val current = book("a", author = "", narrator = " ")
        val all = listOf(item(current), item(book("b", author = "", narrator = "")))
        val links = bookLinks(all, current)
        assertEquals(emptyList(), links.authors)
        assertEquals(emptyList(), links.narrators)
    }

    @Test
    fun `narrators are linked the same way`() {
        val current = book("a", narrator = "Nora Voice")
        val all = listOf(item(current), item(book("b", narrator = "Nora Voice")), item(book("c", narrator = "Other Voice")))
        assertEquals(listOf(PersonLink("nora voice", "Nora Voice", 1)), bookLinks(all, current).narrators)
    }

    @Test
    fun `the current book is counted out even when it is missing from the list`() {
        val current = book("a", author = "Ann Writer", series = "Saga", sequence = "1")
        val all = listOf(item(book("b", author = "Ann Writer", series = "Saga", sequence = "2")))
        val links = bookLinks(all, current)
        assertEquals(listOf(PersonLink("ann writer", "Ann Writer", 1)), links.authors)
        assertEquals(2, links.series!!.bookCount)
    }
}
