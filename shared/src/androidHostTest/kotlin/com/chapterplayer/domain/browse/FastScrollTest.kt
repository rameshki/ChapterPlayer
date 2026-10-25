package com.chapterplayer.domain.browse

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
    fun `a leading The, A or An is skipped`() {
        assertEquals(
            listOf(LetterIndex('E', 0), LetterIndex('H', 1), LetterIndex('W', 2)),
            letters("An Echo", "The Hobbit", "A Wizard")
        )
    }

    @Test
    fun `digits, symbols and other scripts share the hash group`() {
        assertEquals(
            listOf(LetterIndex('#', 0), LetterIndex('A', 2), LetterIndex('Z', 4)),
            letters("1984", "'Salem's Lot", "Anna", "Émile", "Zoo")
        )
    }

    @Test
    fun `an empty or blank key is in the hash group`() {
        assertEquals(listOf(LetterIndex('#', 0), LetterIndex('B', 1)), letters("", "Bee"))
    }

    @Test
    fun `the index follows the list it was given, not the alphabet`() {
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
        assertEquals(emptyList(), fastScrollIndex(nineteen))
        assertEquals(listOf(LetterIndex('B', 0)), fastScrollIndex(nineteen + "Book 19"))
        assertEquals(emptyList(), fastScrollIndex(null))
    }
}
