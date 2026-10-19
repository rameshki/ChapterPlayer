package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseLogicTest {

    private fun book(
        id: String,
        title: String = "Title $id",
        author: String = "Author",
        narrator: String = "Narrator",
        durationMs: Long = 1_000L,
        addedAt: Long = 0L,
        series: String? = null,
        sequence: String? = null,
        serverId: String = "s1",
    ) = AudioBook(
        id = id, title = title, author = author, narrator = narrator, durationMs = durationMs,
        chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = serverId,
        libraryId = "", addedAt = addedAt, seriesName = series, seriesSequence = sequence,
    )

    private fun progress(id: String, position: Long, finished: Boolean = false, lastPlayedAt: Long = 0L) =
        ReadingProgress(bookId = id, positionMs = position, durationMs = 10_000L, lastPlayedAt = lastPlayedAt, isFinished = finished)

    private fun item(book: AudioBook, progress: ReadingProgress? = null, state: DownloadState = DownloadState.NOT_DOWNLOADED) =
        BrowseItem(book, progress, state)

    private fun ids(items: List<BrowseItem>) = items.map { it.book.id }

    // ---- progress filter ----

    private val progressSample = listOf(
        item(book("never"), null),
        item(book("zero"), progress("zero", 0)),
        item(book("half"), progress("half", 5_000)),
        item(book("done"), progress("done", 10_000, finished = true)),
        item(book("doneAtZero"), progress("doneAtZero", 0, finished = true)),
    )

    @Test
    fun `in progress keeps only unfinished books with a position above zero`() {
        val result = applyQuery(progressSample, BrowseQuery(progress = ProgressFilter.IN_PROGRESS, sort = BrowseSort.TITLE))
        assertEquals(listOf("half"), ids(result))
    }

    @Test
    fun `not started keeps books with no progress or an unfinished position of zero`() {
        val result = applyQuery(progressSample, BrowseQuery(progress = ProgressFilter.NOT_STARTED, sort = BrowseSort.TITLE))
        assertEquals(listOf("never", "zero"), ids(result))
    }

    @Test
    fun `finished keeps every finished book even at position zero`() {
        val result = applyQuery(progressSample, BrowseQuery(progress = ProgressFilter.FINISHED, sort = BrowseSort.TITLE))
        assertEquals(listOf("done", "doneAtZero"), ids(result))
    }

    @Test
    fun `progress filter all keeps everything`() {
        val result = applyQuery(progressSample, BrowseQuery(progress = ProgressFilter.ALL, sort = BrowseSort.TITLE))
        assertEquals(5, result.size)
    }

    // ---- download filter ----

    private val downloadSample = listOf(
        item(book("a"), state = DownloadState.DOWNLOADED),
        item(book("b"), state = DownloadState.DOWNLOADING),
        item(book("c"), state = DownloadState.NOT_DOWNLOADED),
    )

    @Test
    fun `downloaded filter keeps only downloaded books`() {
        val result = applyQuery(downloadSample, BrowseQuery(download = DownloadFilter.DOWNLOADED, sort = BrowseSort.TITLE))
        assertEquals(listOf("a"), ids(result))
    }

    @Test
    fun `not downloaded filter keeps downloading and not downloaded books`() {
        val result = applyQuery(downloadSample, BrowseQuery(download = DownloadFilter.NOT_DOWNLOADED, sort = BrowseSort.TITLE))
        assertEquals(listOf("b", "c"), ids(result))
    }

    @Test
    fun `both filters must match and a filter matching nothing gives an empty list`() {
        val items = listOf(
            item(book("a"), progress("a", 5_000), DownloadState.NOT_DOWNLOADED),
            item(book("b"), null, DownloadState.DOWNLOADED),
        )
        val both = applyQuery(items, BrowseQuery(ProgressFilter.IN_PROGRESS, DownloadFilter.DOWNLOADED))
        assertEquals(emptyList(), ids(both))
        val one = applyQuery(items, BrowseQuery(ProgressFilter.IN_PROGRESS, DownloadFilter.NOT_DOWNLOADED))
        assertEquals(listOf("a"), ids(one))
    }

    @Test
    fun `apply query sorts after filtering`() {
        val items = listOf(
            item(book("old", addedAt = 1_000), progress("old", 5_000)),
            item(book("new", addedAt = 3_000), progress("new", 5_000)),
            item(book("fresh", addedAt = 9_000), null),
        )
        val result = applyQuery(items, BrowseQuery(progress = ProgressFilter.IN_PROGRESS, sort = BrowseSort.RECENTLY_ADDED))
        assertEquals(listOf("new", "old"), ids(result))
    }

    // ---- sorting ----

    @Test
    fun `recently added is newest first and unknown dates come last`() {
        val items = listOf(
            item(book("unknown", addedAt = 0)), item(book("mid", addedAt = 2_000)), item(book("newest", addedAt = 5_000)),
        )
        assertEquals(listOf("newest", "mid", "unknown"), ids(sortItems(items, BrowseSort.RECENTLY_ADDED)))
    }

    @Test
    fun `recently played is newest first and never played books come last`() {
        val items = listOf(
            item(book("never"), null),
            item(book("older"), progress("older", 1_000, lastPlayedAt = 100)),
            item(book("newer"), progress("newer", 1_000, lastPlayedAt = 900)),
        )
        assertEquals(listOf("newer", "older", "never"), ids(sortItems(items, BrowseSort.RECENTLY_PLAYED)))
    }

    @Test
    fun `title sort ignores case and one leading article`() {
        val items = listOf(
            item(book("1", title = "The Zebra")), item(book("2", title = "apple")),
            item(book("3", title = "An Owl")), item(book("4", title = "Banana")), item(book("5", title = "A Cat")),
        )
        // apple, Banana, A Cat (C), An Owl (O), The Zebra (Z)
        assertEquals(listOf("2", "4", "5", "3", "1"), ids(sortItems(items, BrowseSort.TITLE)))
    }

    @Test
    fun `title sort only strips an article followed by a space`() {
        val items = listOf(item(book("1", title = "Theory of Everything")), item(book("2", title = "Zoo")), item(book("3", title = "Another")))
        // "Another" is "An" + "other": no space after the article, so it stays under A; "Theory" stays under T.
        assertEquals(listOf("3", "1", "2"), ids(sortItems(items, BrowseSort.TITLE)))
    }

    @Test
    fun `equal titles are ordered by id`() {
        val items = listOf(item(book("b", title = "Same")), item(book("a", title = "Same")))
        assertEquals(listOf("a", "b"), ids(sortItems(items, BrowseSort.TITLE)))
    }

    @Test
    fun `author sort is case insensitive then by title`() {
        val items = listOf(
            item(book("1", title = "Beta", author = "bob")),
            item(book("2", title = "Zulu", author = "Alice")),
            item(book("3", title = "Alpha", author = "alice")),
        )
        assertEquals(listOf("3", "2", "1"), ids(sortItems(items, BrowseSort.AUTHOR)))
    }

    @Test
    fun `length sort is longest first with ties by title`() {
        val items = listOf(
            item(book("1", title = "B", durationMs = 5_000)),
            item(book("2", title = "A", durationMs = 5_000)),
            item(book("3", title = "C", durationMs = 9_000)),
            item(book("4", title = "D", durationMs = 1_000)),
        )
        assertEquals(listOf("3", "2", "1", "4"), ids(sortItems(items, BrowseSort.LENGTH)))
    }

    @Test
    fun `ties in every sort fall back to title then id`() {
        val items = listOf(
            item(book("2", title = "Same", addedAt = 7, durationMs = 3, author = "X")),
            item(book("1", title = "Same", addedAt = 7, durationMs = 3, author = "X")),
            item(book("3", title = "Alpha", addedAt = 7, durationMs = 3, author = "X")),
        )
        for (sort in BrowseSort.values()) {
            assertEquals(listOf("3", "1", "2"), ids(sortItems(items, sort)), "sort $sort")
        }
    }

    // ---- author and narrator groups ----

    @Test
    fun `an author string with several people splits on comma semicolon and ampersand`() {
        val groups = groupByAuthor(listOf(item(book("1", author = "Ann Lee, Bo Chen; Cy Dunn & Di Fox"))))
        assertEquals(listOf("Ann Lee", "Bo Chen", "Cy Dunn", "Di Fox"), groups.map { it.title })
        assertEquals(listOf("ann lee", "bo chen", "cy dunn", "di fox"), groups.map { it.key })
    }

    @Test
    fun `the same person with different capitalisation or spacing is one group named as first seen`() {
        val groups = groupByAuthor(listOf(
            item(book("1", author = "ann   lee")), item(book("2", author = "Ann Lee")), item(book("3", author = "  ANN LEE ")),
        ))
        assertEquals(1, groups.size)
        assertEquals("ann lee", groups[0].title)
        assertEquals("ann lee", groups[0].key)
        assertEquals(listOf("1", "2", "3"), ids(groups[0].items))
    }

    @Test
    fun `a book with two authors appears in both groups and the same person twice counts once`() {
        val groups = groupByAuthor(listOf(item(book("1", author = "Ann, Bob")), item(book("2", author = "Ann, ann"))))
        assertEquals(listOf("Ann", "Bob"), groups.map { it.title })
        assertEquals(listOf("1", "2"), ids(groups[0].items))
        assertEquals(listOf("1"), ids(groups[1].items))
    }

    @Test
    fun `groups are ordered by title ignoring case and items keep the input order`() {
        val groups = groupByAuthor(listOf(
            item(book("1", author = "zed")), item(book("2", author = "Amy")), item(book("3", author = "amy")), item(book("4", author = "Bob")),
        ))
        assertEquals(listOf("Amy", "Bob", "zed"), groups.map { it.title })
        assertEquals(listOf("2", "3"), ids(groups[0].items))
    }

    @Test
    fun `books without a usable author go to the unknown author group placed last`() {
        val groups = groupByAuthor(listOf(
            item(book("1", author = "")), item(book("2", author = "Zed")), item(book("3", author = " ,; & ")), item(book("4", author = "Amy")),
        ))
        assertEquals(listOf("Amy", "Zed", "Unknown author"), groups.map { it.title })
        assertEquals("unknown", groups.last().key)
        assertEquals(listOf("1", "3"), ids(groups.last().items))
    }

    @Test
    fun `narrators split like authors and the unknown group says narrator`() {
        val groups = groupByNarrator(listOf(
            item(book("1", narrator = "Eve Ray & Finn Poe")), item(book("2", narrator = "")), item(book("3", narrator = "eve ray")),
        ))
        assertEquals(listOf("Eve Ray", "Finn Poe", "Unknown narrator"), groups.map { it.title })
        assertEquals("unknown", groups.last().key)
        assertEquals(listOf("1", "3"), ids(groups[0].items))
        assertEquals(listOf("2"), ids(groups.last().items))
    }

    // ---- series groups ----

    @Test
    fun `series books are ordered by numeric sequence with missing sequences last`() {
        val groups = groupBySeries(listOf(
            item(book("ten", series = "Saga", sequence = "10")), item(book("none", series = "Saga", sequence = null)),
            item(book("two", series = "Saga", sequence = "2")), item(book("threeHalf", series = "Saga", sequence = "3.5")),
            item(book("junk", series = "Saga", sequence = "x")),
        ))
        assertEquals(1, groups.size)
        assertEquals(listOf("two", "threeHalf", "ten", "junk", "none"), ids(groups[0].items))
    }

    @Test
    fun `series books with equal sequence are ordered by title`() {
        val groups = groupBySeries(listOf(
            item(book("1", title = "Beta", series = "Saga", sequence = "1")), item(book("2", title = "Alpha", series = "Saga", sequence = "1")),
        ))
        assertEquals(listOf("2", "1"), ids(groups[0].items))
    }

    @Test
    fun `books with no or blank series are left out and names merge by lowercase`() {
        val groups = groupBySeries(listOf(
            item(book("1", series = null)), item(book("2", series = "   ")), item(book("3", series = "Saga")),
            item(book("4", series = " saga ")), item(book("5", series = "")),
        ))
        assertEquals(1, groups.size)
        assertEquals("saga", groups[0].key)
        assertEquals("Saga", groups[0].title)
        assertEquals(listOf("3", "4"), ids(groups[0].items))
    }

    @Test
    fun `series groups are ordered by title ignoring case and one leading article`() {
        val groups = groupBySeries(listOf(
            item(book("1", series = "Gamma")), item(book("2", series = "The Beta Chronicles")), item(book("3", series = "alpha")),
        ))
        assertEquals(listOf("alpha", "The Beta Chronicles", "Gamma"), groups.map { it.title })
    }

    // ---- collections ----

    @Test
    fun `collection groups keep the collection order and the order of their book ids`() {
        val items = listOf(item(book("a")), item(book("b")), item(book("c")))
        val groups = groupByCollection(items, listOf(
            BookCollection("s1", "col2", "Second", listOf("c", "a")),
            BookCollection("s1", "col1", "First", listOf("b", "c", "a")),
        ))
        assertEquals(listOf("Second", "First"), groups.map { it.title })
        assertEquals(listOf("s1/col2", "s1/col1"), groups.map { it.key })
        assertEquals(listOf("c", "a"), ids(groups[0].items))
        assertEquals(listOf("b", "c", "a"), ids(groups[1].items))
    }

    @Test
    fun `collections ignore unknown ids books of another server and skip empty results`() {
        val items = listOf(item(book("a", serverId = "s1")), item(book("a2", serverId = "s2")))
        val groups = groupByCollection(items, listOf(
            BookCollection("s1", "one", "One", listOf("missing", "a", "a2")),
            BookCollection("s1", "none", "Nothing here", listOf("missing")),
            BookCollection("s1", "empty", "Empty", emptyList()),
        ))
        assertEquals(1, groups.size)
        assertEquals(listOf("a"), ids(groups[0].items))
    }

    @Test
    fun `a big collection over a big library stays fast`() {
        val items = (1..3_000).map { item(book("b$it")) }
        val collection = BookCollection("s1", "big", "Big", (3_000 downTo 1).map { "b$it" })
        val start = System.nanoTime()
        val groups = groupByCollection(items, List(20) { collection })
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(20, groups.size)
        assertEquals("b3000", groups[0].items.first().book.id)
        check(millis < 3_000) { "took $millis ms" }
    }

    // ---- findings from the final review ----

    @Test
    fun `the same book id on two servers lands in the collection of its own server`() {
        val first = item(book("x", title = "On first", serverId = "s1"))
        val second = item(book("x", title = "On second", serverId = "s2"))
        val groups = groupByCollection(listOf(first, second), listOf(
            BookCollection("s1", "c1", "One", listOf("x")),
            BookCollection("s2", "c2", "Two", listOf("x")),
        ))
        assertEquals(listOf("On first"), groups[0].items.map { it.book.title })
        assertEquals(listOf("On second"), groups[1].items.map { it.book.title })
    }

    @Test
    fun `a book id listed twice in a collection appears once at its first position`() {
        val groups = groupByCollection(listOf(item(book("a")), item(book("b"))), listOf(
            BookCollection("s1", "c", "C", listOf("b", "a", "b", "a")),
        ))
        assertEquals(listOf("b", "a"), ids(groups[0].items))
    }

    @Test
    fun `an identical item given twice stays twice but a repeated person counts once per book`() {
        val same = item(book("1", author = "Ann, ANN"))
        val groups = groupByAuthor(listOf(same, same))
        assertEquals(1, groups.size)
        assertEquals(listOf("1", "1"), ids(groups[0].items))
    }

    @Test
    fun `title sort ties that differ only in case are ordered by id`() {
        val items = listOf(item(book("9", title = "apple")), item(book("1", title = "Apple")))
        assertEquals(listOf("1", "9"), ids(sortItems(items, BrowseSort.TITLE)))
    }

    @Test
    fun `a played book with a last played time of zero sorts before a never played book`() {
        val items = listOf(item(book("never"), null), item(book("zeroPlayed"), progress("zeroPlayed", 1_000, lastPlayedAt = 0)))
        assertEquals(listOf("zeroPlayed", "never"), ids(sortItems(items, BrowseSort.RECENTLY_PLAYED)))
    }

    @Test
    fun `series sequences that are not finite numbers count as missing`() {
        val groups = groupBySeries(listOf(
            item(book("inf", series = "S", sequence = "Infinity")), item(book("two", series = "S", sequence = "2")),
            item(book("nan", series = "S", sequence = "NaN")), item(book("one", series = "S", sequence = "1")),
        ))
        assertEquals(listOf("one", "two", "inf", "nan"), ids(groups[0].items))
    }

    @Test
    fun `series groups whose titles sort the same are ordered by key whatever the input order`() {
        val groups = groupBySeries(listOf(item(book("1", series = "The Saga")), item(book("2", series = "Saga"))))
        assertEquals(listOf("Saga", "The Saga"), groups.map { it.title })
    }

    // ---- separators inside brackets ----

    @Test
    fun `a separator inside parentheses does not split the name`() {
        val groups = groupByAuthor(listOf(item(book("1", author = "Era One (2,500 to 800 BE)"))))
        assertEquals(listOf("Era One (2,500 to 800 BE)"), groups.map { it.title })
    }

    @Test
    fun `people outside the parentheses still split while the bracketed part stays together`() {
        val groups = groupByAuthor(listOf(item(book("1", author = "Ann Lee, Era Two (1,000 BE); Bo Chen"))))
        assertEquals(listOf("Ann Lee", "Bo Chen", "Era Two (1,000 BE)"), groups.map { it.title })
    }

    @Test
    fun `square brackets and nested brackets also protect their separators`() {
        val groups = groupByAuthor(listOf(
            item(book("1", author = "Name [a, b]")),
            item(book("2", author = "Outer (inner (c, d), e)")),
            item(book("3", author = "Smith (Jr & Sr)")),
        ))
        assertEquals(listOf("Name [a, b]", "Outer (inner (c, d), e)", "Smith (Jr & Sr)"), groups.map { it.title })
    }

    @Test
    fun `an unclosed bracket keeps the rest of the text together and an extra closing bracket does not break splitting`() {
        val open = groupByAuthor(listOf(item(book("1", author = "Ann (x, y"))))
        assertEquals(listOf("Ann (x, y"), open.map { it.title })
        val extra = groupByAuthor(listOf(item(book("2", author = "Ann ), Bo"))))
        assertEquals(listOf("Ann )", "Bo"), extra.map { it.title })
    }

    @Test
    fun `narrators protect bracketed separators the same way`() {
        val groups = groupByNarrator(listOf(item(book("1", narrator = "Eve Ray (reader, 2 of 3) & Finn Poe"))))
        assertEquals(listOf("Eve Ray (reader, 2 of 3)", "Finn Poe"), groups.map { it.title })
    }
}
