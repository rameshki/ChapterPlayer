package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FastScrollTest {

    private fun letters(vararg keys: String) = fastScrollLetters(keys.toList())

    @Test
    fun `each letter points at the first key that starts with it`() {
        assertEquals(
            listOf(LetterIndex('A', 0), LetterIndex('B', 2), LetterIndex('D', 3)),
            letters("Apple", "avocado", "Banana", "Date", "dune")
        )
    }

    @Test
    fun `a leading The - A or An is skipped`() {
        assertEquals(
            listOf(LetterIndex('E', 0), LetterIndex('H', 1), LetterIndex('W', 2)),
            letters("An Echo", "The Hobbit", "A Wizard")
        )
    }

    @Test
    fun `digits - symbols and other scripts share the hash group and accents count as their letter`() {
        assertEquals(
            listOf(LetterIndex('#', 0), LetterIndex('A', 3), LetterIndex('E', 4), LetterIndex('Z', 5)),
            letters("1984", "'Salem's Lot", "Война и мир", "Anna", "Émile", "Zoo")
        )
    }

    @Test
    fun `an empty or blank key is in the hash group`() {
        assertEquals(listOf(LetterIndex('#', 0), LetterIndex('B', 1)), letters("", "Bee"))
    }

    @Test
    fun `the index follows the list it was given - not the alphabet`() {
        // An out-of-order list keeps its own order and the first position of each letter.
        assertEquals(
            listOf(LetterIndex('C', 0), LetterIndex('A', 1), LetterIndex('B', 2)),
            letters("Cat", "Ant", "Bee", "Cow", "Axe")
        )
    }

    @Test
    fun `no keys give no letters`() {
        assertEquals(emptyList(), letters())
    }

    @Test
    fun `a missing letter has no index and a present one has its first position`() {
        val index = letters("Apple", "Banana", "Bread", "Date")
        assertEquals(1, indexForLetter(index, 'B'))
        assertEquals(3, indexForLetter(index, 'D'))
        assertNull(indexForLetter(index, 'C'))
        assertNull(indexForLetter(index, '#'))
    }

    @Test
    fun `the strip runs from hash to Z`() {
        assertEquals(27, FAST_SCROLL_ALPHABET.size)
        assertEquals('#', FAST_SCROLL_ALPHABET.first())
        assertEquals('A', FAST_SCROLL_ALPHABET[1])
        assertEquals('Z', FAST_SCROLL_ALPHABET.last())
    }

    @Test
    fun `the top of the strip is the first present letter and the bottom the last`() {
        val index = letters("1984", "Apple", "Mango", "Zebra")
        assertEquals(LetterIndex('#', 0), letterAtFraction(index, 0f))
        assertEquals(LetterIndex('Z', 3), letterAtFraction(index, 1f))
    }

    @Test
    fun `the ends still find a letter when hash and Z are missing`() {
        val index = letters("Bee", "Mango", "Yak")
        assertEquals(LetterIndex('B', 0), letterAtFraction(index, 0f))
        assertEquals(LetterIndex('Y', 2), letterAtFraction(index, 1f))
    }

    @Test
    fun `fractions outside the strip are clamped`() {
        val index = letters("Apple", "Zebra")
        assertEquals(LetterIndex('A', 0), letterAtFraction(index, -0.5f))
        assertEquals(LetterIndex('Z', 1), letterAtFraction(index, 3f))
    }

    @Test
    fun `a missing letter under the finger keeps the present letter above it`() {
        val index = letters("Apple", "Mango", "Zebra")
        // 'C' is the fourth slot of 27.
        val onC = (3 + 0.5f) / 27f
        assertEquals(LetterIndex('A', 0), letterAtFraction(index, onC))
        val onM = (13 + 0.5f) / 27f
        assertEquals(LetterIndex('M', 1), letterAtFraction(index, onM))
    }

    @Test
    fun `no letters give no letter at any fraction`() {
        assertNull(letterAtFraction(emptyList(), 0.5f))
    }

    @Test
    fun `the scroller shows only from the minimum list size`() {
        val nineteen = List(19) { "Book $it" }
        assertEquals(emptyList(), fastScrollIndex(FastScrollKeys(nineteen, LetterRule.TITLE)))
        assertEquals(listOf(LetterIndex('B', 0)), fastScrollIndex(FastScrollKeys(nineteen + "Book 19", LetterRule.TITLE)))
        assertEquals(emptyList(), fastScrollIndex(null))
    }

    // ---- names, accents and the real sorts ----

    @Test
    fun `names keep a leading article and fold accents`() {
        assertEquals('T', personLetter("The Great Courses"))
        assertEquals('A', personLetter("A. J. Finn"))
        assertEquals('A', personLetter("A J Finn"))
        assertEquals('E', personLetter("Émile Zola"))
        assertEquals('#', personLetter("Лев Толстой"))
        assertEquals('#', personLetter("  "))
        assertEquals('G', titleLetterOf("The Great Gatsby"))
        assertEquals('O', titleLetterOf("Ödipus"))
    }

    private fun book(id: String, title: String = "Title $id", author: String = "Author") = BrowseItem(
        AudioBook(
            id = id, title = title, author = author, narrator = author, durationMs = 1_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
        ),
        null,
        DownloadState.NOT_DOWNLOADED
    )

    /** Each letter's items are one block and the blocks come in strip order ('#' first, then A to Z). */
    private fun assertContiguousInStripOrder(keys: List<String>, rule: LetterRule) {
        val perItem = keys.map { if (rule == LetterRule.TITLE) titleLetterOf(it) else personLetter(it) }
        val blocks = perItem.fold(mutableListOf<Char>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
        assertEquals(blocks.distinct(), blocks, "a letter is split in $keys")
        assertEquals(blocks.sortedBy { FAST_SCROLL_ALPHABET.indexOf(it) }, blocks, "letters out of strip order in $keys")
        val index = fastScrollLetters(keys, rule)
        assertEquals(blocks, index.map { it.letter })
        index.forEach { assertEquals(it.letter, perItem[it.firstIndex]) }
    }

    private val authors = listOf(
        "The Great Courses", "Émile Zola", "Ernest Hemingway", "A. J. Finn", "zadie smith", "Ödön von Horváth",
        "Ottessa Moshfegh", "Лев Толстой", "50 Cent", "Tana French", "Bob", "Anne Rice", "Élodie Harper"
    )

    @Test
    fun `the Author sort gives one block per letter with the person letters`() {
        val sorted = sortItems(authors.mapIndexed { i, a -> book("b$i", author = a) }, BrowseSort.AUTHOR)
        assertContiguousInStripOrder(sorted.map { it.book.author }, LetterRule.PERSON)
        // "The Great Courses" is under T, next to Tana French, not under G.
        val keys = sorted.map { it.book.author }
        assertEquals(keys.indexOf("Tana French") + 1, keys.indexOf("The Great Courses"))
    }

    @Test
    fun `the Authors list gives one block per letter with the person letters`() {
        val groups = groupByAuthor(authors.mapIndexed { i, a -> book("b$i", author = a) })
        assertContiguousInStripOrder(groups.map { it.title }, LetterRule.PERSON)
        assertEquals('#', personLetter(groups.first().title))
    }

    @Test
    fun `the Title sort gives one block per letter with the title letters`() {
        val titles = listOf(
            "The Great Gatsby", "Émile", "Echo", "An Elephant", "1984", "Œdipus", "Война и мир", "zebra", "Ölmez",
            "A Man Called Ove", "'Salem's Lot", "Gone Girl", "Ältere Geschichten"
        )
        val sorted = sortItems(titles.mapIndexed { i, t -> book("b$i", title = t) }, BrowseSort.TITLE)
        assertContiguousInStripOrder(sorted.map { it.book.title }, LetterRule.TITLE)
        // The '#' group (digits, symbols, other scripts) is one block at the top, where '#' is on the strip.
        assertEquals(setOf("1984", "'Salem's Lot", "Война и мир"), sorted.take(3).map { it.book.title }.toSet())
    }

    @Test
    fun `series names letter and sort the same way`() {
        val names = listOf("The Expanse", "Ériador", "Discworld", "1Q84", "Œuvres")
        val groups = groupBySeries(names.mapIndexed { i, n -> book("b$i").let { it.copy(book = it.book.copy(seriesName = n)) } })
        assertContiguousInStripOrder(groups.map { it.title }, LetterRule.TITLE)
    }
}
