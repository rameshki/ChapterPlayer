package com.chapterplayer.domain.car

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarBrowseTest {

    private fun book(
        id: String,
        title: String,
        author: String = "Jane Doe",
        addedAt: Long = 0,
        series: String? = null,
        seq: String? = null,
        source: ServerSource = ServerSource.ABS,
        state: DownloadState = DownloadState.NOT_DOWNLOADED,
        progress: ReadingProgress? = null,
    ) = BrowseItem(
        AudioBook(
            id = id, title = title, author = author, narrator = "", durationMs = 10_000_000, chapters = emptyList(),
            streamUrl = "", coverUrl = "cover/$id", source = source, serverId = "s1", addedAt = addedAt,
            seriesName = series, seriesSequence = seq,
        ),
        progress,
        state,
    )

    private fun progress(id: String, pos: Long, last: Long, finished: Boolean = false) =
        ReadingProgress(id, pos, 10_000_000, last, finished)

    private fun data(vararg items: BrowseItem, collections: List<BookCollection> = emptyList()) =
        CarData(items.toList(), collections)

    private fun ids(list: List<CarItem>?) = list?.map { it.id }

    @Test
    fun `the root has the four tabs`() {
        assertEquals(listOf("Continue", "Recently added", "Library", "Downloaded"), carRoot().map { it.title })
        assertTrue(carRoot().all { it.browsable && !it.playable })
        assertEquals(carRoot(), carChildren("car:root", data(), true))
    }

    @Test
    fun `continue lists unfinished started books, most recently played first`() {
        val d = data(
            book("a", "A", progress = progress("a", 1_000, last = 10)),
            book("b", "B", progress = progress("b", 2_000, last = 30)),
            book("c", "C"),
            book("d", "D", progress = progress("d", 0, last = 40)),
            book("e", "E", progress = progress("e", 5_000, last = 50, finished = true)),
            book("f", "F", progress = progress("f", 9_997_000, last = 60)),
        )
        assertEquals(listOf("b", "a"), ids(carChildren("car:continue", d, true)))
    }

    @Test
    fun `recently added lists the newest first and is capped`() {
        val many = (1..60).map { book("b$it", "Title $it", addedAt = it.toLong()) }
        val list = carChildren("car:recent", CarData(many, emptyList()), true)!!
        assertEquals(CAR_MAX_SHORT_LIST, list.size)
        assertEquals("b60", list.first().id)
    }

    @Test
    fun `the library offers series, authors, collections and all books`() {
        assertEquals(
            listOf("Series", "Authors", "Collections", "All books"),
            carChildren("car:library", data(), true)!!.map { it.title }
        )
    }

    @Test
    fun `series lists its books in order`() {
        val d = data(
            book("s2", "Second", series = "Saga", seq = "2"),
            book("s1", "First", series = "Saga", seq = "1"),
            book("x", "Other"),
        )
        val series = carChildren("car:series", d, true)!!
        assertEquals(listOf("Saga"), series.map { it.title })
        assertEquals("2 books", series.single().subtitle)
        assertEquals(listOf("s1", "s2"), ids(carChildren(series.single().id, d, true)))
    }

    @Test
    fun `authors and collections list their books`() {
        val d = data(
            book("a", "Alpha", author = "Ann Lee"),
            book("b", "Beta", author = "Bob Ray"),
            collections = listOf(BookCollection("s1", "c1", "Favourites", listOf("b", "a"))),
        )
        val authors = carChildren("car:authors", d, true)!!
        assertEquals(listOf("Ann Lee", "Bob Ray"), authors.map { it.title })
        assertEquals(listOf("b"), ids(carChildren(authors[1].id, d, true)))
        val cols = carChildren("car:collections", d, true)!!
        assertEquals(listOf("Favourites"), cols.map { it.title })
        assertEquals(listOf("b", "a"), ids(carChildren(cols.single().id, d, true)))
    }

    @Test
    fun `all books are grouped into letter ranges`() {
        val d = data(book("a", "Apple"), book("b", "Banana"), book("t", "The Dog"))
        val ranges = carChildren("car:all", d, true)!!
        assertEquals(listOf("A to D"), ranges.map { it.title })
        assertEquals("3 books", ranges.single().subtitle)
        assertEquals(listOf("a", "b", "t"), ids(carChildren(ranges.single().id, d, true)))
    }

    @Test
    fun `with nothing downloaded the tab holds one note`() {
        val d = data(book("a", "A"), book("b", "B", state = DownloadState.DOWNLOADING))
        for (online in listOf(true, false)) {
            val items = carChildren("car:downloaded", d, online)!!
            assertEquals(listOf(CAR_NO_DOWNLOADS_NOTE), items.map { it.title })
            assertEquals("No downloaded books yet", items.single().title)
            assertTrue(!items.single().playable && !items.single().browsable)
        }
    }

    @Test
    fun `downloaded lists only the books on the phone, by title, with the usual rows`() {
        val d = data(
            book("c", "Cherry", state = DownloadState.DOWNLOADED, progress = progress("c", 5_000_000, 1)),
            book("a", "Apple"),
            book("b", "banana", state = DownloadState.DOWNLOADED),
            book("d", "Date", state = DownloadState.DOWNLOADING),
        )
        val items = carChildren("car:downloaded", d, online = true)!!
        assertEquals(listOf("b", "c"), ids(items))
        assertEquals(carBookItem(d.items[0], online = true), items[1])
        assertTrue(items.all { it.playable && !it.dimmed && !it.browsable })
    }

    @Test
    fun `offline the downloaded books still play and the tab has no banner`() {
        val d = data(book("a", "A"), book("b", "B", state = DownloadState.DOWNLOADED))
        val items = carChildren("car:downloaded", d, online = false)!!
        assertEquals(listOf("b"), ids(items))
        assertTrue(items.single().playable)
        assertTrue(!items.single().dimmed)
    }

    @Test
    fun `an unknown or broken node has no children`() {
        assertNull(carChildren("car:nope", data(), true))
        assertNull(carChildren("abs_1", data(), true))
        assertNull(carChildren("car:author:nobody", data(book("a", "A")), true))
        assertNull(carChildren("car:all:Z", data(book("a", "A")), true))
    }

    @Test
    fun `a book row shows author and progress and is playable online`() {
        val row = carBookItem(book("a", "A", progress = progress("a", 4_500_000, last = 1)), online = true)
        assertEquals("a", row.id)
        assertEquals("cover/a", row.coverUrl)
        assertEquals("Jane Doe · 45% listened", row.subtitle)
        assertTrue(row.playable && !row.dimmed && !row.browsable)
    }

    @Test
    fun `offline a book that is not downloaded is dimmed and cannot play, and the list starts with a banner`() {
        val d = data(book("a", "A"), book("b", "B", state = DownloadState.DOWNLOADED))
        val list = carChildren("car:recent", d, online = false)!!
        assertEquals("car:downloaded", list.first().id)
        assertTrue(list.first().browsable)
        val a = list.first { it.id == "a" }
        val b = list.first { it.id == "b" }
        assertTrue(!a.playable && a.dimmed)
        assertEquals("Jane Doe · Needs a connection", a.subtitle)
        assertTrue(b.playable && !b.dimmed)
    }

    @Test
    fun `a plex book is shown but cannot play`() {
        val row = carBookItem(book("p", "P", source = ServerSource.PLEX), online = true)
        assertEquals(CAR_PLEX_NOTE, row.subtitle)
        assertTrue(!row.playable && row.dimmed)
    }
}
