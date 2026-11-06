package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavouritesTest {

    private fun item(id: String, serverId: String = "abs1", title: String = "Book $id", finished: Boolean = false) = BrowseItem(
        AudioBook(
            id = id, title = title, author = "Someone", narrator = "", durationMs = 1_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null,
            source = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS, serverId = serverId,
        ),
        if (finished) ReadingProgress(id, 1_000L, 1_000L, 5L, isFinished = true) else null,
        DownloadState.NOT_DOWNLOADED,
    )

    private fun merged(primary: BrowseItem, other: BrowseItem) = primary.copy(sources = listOf(primary, other))

    private fun ids(items: List<BrowseItem>) = items.map { it.book.id }

    @Test
    fun `a book is a favourite when its id is stored`() {
        assertTrue(isFavourite(item("a"), setOf("a")))
        assertFalse(isFavourite(item("a"), setOf("b")))
        assertFalse(isFavourite(item("a"), emptySet()))
    }

    @Test
    fun `a merged book is a favourite when any copy is`() {
        val entry = merged(item("a"), item("p", "plex1"))
        assertTrue(isFavourite(entry, setOf("p")))
        assertTrue(isFavourite(entry, setOf("a")))
        assertFalse(isFavourite(entry, setOf("x")))
    }

    @Test
    fun `marking and unmarking cover every copy of the entry`() {
        assertEquals(listOf("a", "p"), favouriteRowIds(merged(item("a"), item("p", "plex1"))))
        assertEquals(listOf("a"), favouriteRowIds(item("a")))
    }

    @Test
    fun `the favourites filter keeps only favourite entries and combines with the others`() {
        val items = listOf(item("a"), item("b", finished = true), item("c", finished = true))
        val query = BrowseQuery(favouritesOnly = true, sort = BrowseSort.TITLE)
        assertEquals(listOf("a", "b"), ids(applyQuery(items, query, favouriteIds = setOf("a", "b"))))
        assertEquals(listOf("b"), ids(applyQuery(items, query.copy(progress = ProgressFilter.FINISHED), favouriteIds = setOf("a", "b"))))
        assertEquals(emptyList(), ids(applyQuery(items, query)))
        // Off, the ids change nothing.
        assertEquals(listOf("a", "b", "c"), ids(applyQuery(items, BrowseQuery(sort = BrowseSort.TITLE), favouriteIds = setOf("a"))))
    }

    @Test
    fun `the favourites filter keeps a merged book shown as another server's copy`() {
        val entry = merged(item("a"), item("p", "plex1"))
        val shown = applyQuery(listOf(entry), BrowseQuery(serverIds = setOf("plex1"), favouritesOnly = true), favouriteIds = setOf("a"))
        assertEquals(listOf("p"), ids(shown))
    }

    @Test
    fun `a stored favourite whose book is gone shows nothing`() {
        assertEquals(emptyList(), favouritesShelf(listOf(item("a")), setOf("gone")))
    }

    @Test
    fun `the shelf lists favourites by title up to the limit`() {
        val items = listOf(item("1", title = "Zebra"), item("2", title = "Apple"), item("3", title = "Mango"))
        assertEquals(listOf("2", "1"), ids(favouritesShelf(items, setOf("1", "2"))))
        assertEquals(listOf("2"), ids(favouritesShelf(items, setOf("1", "2"), limit = 1)))
        assertEquals(emptyList(), favouritesShelf(items, emptySet()))
    }

    @Test
    fun `the favourites chip stands for the favourites filter alone`() {
        val query = queryFor(FilterChip.FAVOURITES, BrowseQuery(ProgressFilter.FINISHED, DownloadFilter.DOWNLOADED, BrowseSort.LENGTH))
        assertEquals(BrowseQuery(sort = BrowseSort.LENGTH, favouritesOnly = true), query)
        assertEquals(FilterChip.FAVOURITES, chipFor(query))
        // Another chip turns it off.
        assertEquals(BrowseQuery(ProgressFilter.FINISHED, sort = BrowseSort.LENGTH), queryFor(FilterChip.FINISHED, query))
        assertNull(chipFor(BrowseQuery(ProgressFilter.FINISHED, favouritesOnly = true)))
        assertTrue(isFiltering(BrowseQuery(favouritesOnly = true)))
    }

    @Test
    fun `the favourites chip is offered with and without downloads`() {
        assertTrue(FilterChip.FAVOURITES in offeredChips(downloads = false))
        assertTrue(FilterChip.FAVOURITES in offeredChips(downloads = true))
    }
}
