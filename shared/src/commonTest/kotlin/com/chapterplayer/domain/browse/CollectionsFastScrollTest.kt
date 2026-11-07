package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionsFastScrollTest {

    private fun item(id: String, serverId: String = "s1") = BrowseItem(
        book = AudioBook(
            id = id, title = "Book $id", author = "Someone", narrator = "", durationMs = 1_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = serverId,
        ),
        progress = null,
        downloadState = DownloadState.NOT_DOWNLOADED,
    )

    @Test
    fun `collections are listed by name with the title rule across servers`() {
        val items = listOf(item("a"), item("b"), item("c", serverId = "s2"))
        val groups = groupByCollection(items, listOf(
            BookCollection("s1", "c1", "Zebra stories", listOf("a")),
            BookCollection("s2", "c2", "The Bedtime pile", listOf("c")),
            BookCollection("s1", "c3", "Études", listOf("b")),
            BookCollection("s1", "c4", "apple picks", listOf("a", "b")),
        ))
        assertEquals(listOf("apple picks", "The Bedtime pile", "Études", "Zebra stories"), groups.map { it.title })
    }

    @Test
    fun `two collections with the same name keep a stable order by key`() {
        val items = listOf(item("a"), item("b", serverId = "s2"))
        val groups = groupByCollection(items, listOf(
            BookCollection("s2", "x", "Favourites", listOf("b")),
            BookCollection("s1", "y", "Favourites", listOf("a")),
        ))
        assertEquals(listOf("s1/y", "s2/x"), groups.map { it.key })
    }

    @Test
    fun `the collection names give the scroller letters with the title rule`() {
        // "Ax" not "A": a leading "A " is an article under the title rule.
        val names = List(20) { i -> "${'A' + i}x collection" } + listOf("The Zoo", "1984 set")
        val sorted = names.sortedBy { titleSortKey(it) }
        val letters = fastScrollIndex(FastScrollKeys(sorted, LetterRule.TITLE))
        assertEquals(LetterIndex('#', 0), letters.first())
        assertEquals('Z', letters.last().letter)
        assertEquals(22, letters.size)
    }

    @Test
    fun `fewer than twenty collections have no scroller`() {
        val names = List(19) { i -> "${'A' + i} collection" }
        assertEquals(emptyList(), fastScrollIndex(FastScrollKeys(names, LetterRule.TITLE)))
    }
}
