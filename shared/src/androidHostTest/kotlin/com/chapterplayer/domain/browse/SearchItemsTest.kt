package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchItemsTest {

    private fun item(
        id: String,
        title: String = "Title $id",
        author: String = "Author",
        narrator: String = "Narrator",
        series: String? = null,
    ) = BrowseItem(
        AudioBook(
            id = id, title = title, author = author, narrator = narrator, durationMs = 1_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
            seriesName = series,
        ),
        progress = null,
        downloadState = DownloadState.NOT_DOWNLOADED,
    )

    private fun ids(items: List<BrowseItem>) = items.map { it.book.id }

    private val library = listOf(
        item("1", title = "The Hobbit", author = "J. R. R. Tolkien", narrator = "Rob Inglis", series = "Middle-earth"),
        item("2", title = "Dune", author = "Frank Herbert", narrator = "Scott Brick", series = "Dune Chronicles"),
        item("3", title = "Emma", author = "Jane Austen", narrator = "Juliet Stevenson"),
    )

    @Test
    fun `it matches the title ignoring case`() {
        assertEquals(listOf("1"), ids(searchItems(library, "hobbit")))
        assertEquals(listOf("1"), ids(searchItems(library, "HOBBIT")))
    }

    @Test
    fun `it matches the author the narrator and the series`() {
        assertEquals(listOf("2"), ids(searchItems(library, "herbert")))
        assertEquals(listOf("3"), ids(searchItems(library, "juliet")))
        assertEquals(listOf("2"), ids(searchItems(library, "chronicles")))
    }

    @Test
    fun `a part of a word matches`() {
        assertEquals(listOf("1"), ids(searchItems(library, "tolk")))
    }

    @Test
    fun `every word of the query must match somewhere in the book`() {
        assertEquals(listOf("1"), ids(searchItems(library, "hobbit tolkien")))
        assertEquals(listOf("1"), ids(searchItems(library, "tolkien hobbit inglis")))
        assertEquals(emptyList(), ids(searchItems(library, "hobbit herbert")))
    }

    @Test
    fun `the query is trimmed and extra spaces between words are ignored`() {
        assertEquals(listOf("2"), ids(searchItems(library, "   dune    brick  ")))
    }

    @Test
    fun `a blank query finds nothing`() {
        assertEquals(emptyList(), ids(searchItems(library, "")))
        assertEquals(emptyList(), ids(searchItems(library, "    ")))
    }

    @Test
    fun `results keep the input order`() {
        val items = listOf(item("b", title = "Zeta Saga"), item("a", title = "Alpha Saga"), item("c", title = "Other"))
        assertEquals(listOf("b", "a"), ids(searchItems(items, "saga")))
    }

    @Test
    fun `a book without a series is still found by its other fields`() {
        assertEquals(listOf("3"), ids(searchItems(library, "emma")))
    }

    @Test
    fun `a word that only appears across two fields does not match`() {
        // "Hobbit" ends the title and "Tolkien" starts the author: the text "hobbittolkien" must not match.
        val items = listOf(item("x", title = "Hobbit", author = "Tolkien"))
        assertEquals(emptyList(), ids(searchItems(items, "hobbittolkien")))
    }
}
