package com.chapterplayer.domain.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarRulesTest {

    // ---- node ids ----

    @Test
    fun `node ids are built and parsed`() {
        assertEquals("car:continue", carNodeId("continue"))
        assertEquals("car:series:abc", carNodeId("series", "abc"))
        assertEquals(CarNode("continue", null), parseCarNodeId("car:continue"))
        assertEquals(CarNode("series", "abc"), parseCarNodeId("car:series:abc"))
    }

    @Test
    fun `an argument may contain colons`() {
        assertEquals(CarNode("author", "a:b"), parseCarNodeId("car:author:a:b"))
        assertEquals(CarNode("author", "a:b"), parseCarNodeId(carNodeId("author", "a:b")))
    }

    @Test
    fun `a book id or a broken id is not a node`() {
        assertNull(parseCarNodeId("abs_123"))
        assertNull(parseCarNodeId("car:"))
        assertNull(parseCarNodeId("car"))
        assertNull(parseCarNodeId(""))
    }

    // ---- trust ----

    @Test
    fun `the own app and the known car packages are trusted, everything else is not`() {
        assertEquals(CarTrust.OWN, isTrustedCarController("com.chapterplayer", "com.chapterplayer"))
        assertEquals(CarTrust.OWN, isTrustedCarController("com.chapterplayer.debug", "com.chapterplayer.debug"))
        assertEquals(CarTrust.CAR, isTrustedCarController("com.google.android.projection.gearhead", "com.chapterplayer"))
        assertEquals(CarTrust.CAR, isTrustedCarController("com.google.android.googlequicksearchbox", "com.chapterplayer"))
        assertEquals(CarTrust.NONE, isTrustedCarController("com.evil.app", "com.chapterplayer"))
        assertEquals(CarTrust.NONE, isTrustedCarController("com.google.android.projection.gearhead.evil", "com.chapterplayer"))
        assertEquals(CarTrust.NONE, isTrustedCarController(null, "com.chapterplayer"))
    }

    // ---- letter ranges ----

    private fun titles(vararg pairs: Pair<String, Int>): List<String> =
        pairs.flatMap { (t, n) -> (1..n).map { "$t$it" } }

    @Test
    fun `few titles make one range`() {
        val ranges = letterRanges(listOf("Apple", "Banana", "Cherry", "The Dog"))
        assertEquals(listOf("A to D"), ranges.map { it.label })
        assertEquals('A', ranges.single().first)
        assertEquals('D', ranges.single().last)
    }

    @Test
    fun `a leading article is ignored and case does not matter`() {
        assertEquals('D', titleLetter("The Dog"))
        assertEquals('M', titleLetter("A Man Called Ove"))
        assertEquals('E', titleLetter("An Elephant"))
        assertEquals('T', titleLetter("Theory of Everything"))
        assertEquals('B', titleLetter("banana"))
        assertEquals('#', titleLetter("1984"))
        assertEquals('#', titleLetter("  "))
    }

    @Test
    fun `ranges close before they pass 25 titles and a big letter stands alone`() {
        val all = titles("A" to 30, "B" to 5, "C" to 2, "D" to 20)
        val ranges = letterRanges(all)
        assertEquals(listOf("A", "B to C", "D"), ranges.map { it.label })
    }

    @Test
    fun `numbers and symbols form the first range`() {
        val ranges = letterRanges(listOf("1984", "Apple"))
        assertEquals(listOf("# to A"), ranges.map { it.label })
        assertEquals(listOf("#"), letterRanges(listOf("1984", "2001")).map { it.label })
    }

    @Test
    fun `no titles make no ranges and a title falls in exactly its range`() {
        assertEquals(emptyList(), letterRanges(emptyList()))
        val ranges = letterRanges(titles("A" to 30, "B" to 5, "C" to 2))
        val b = ranges.first { it.label == "B to C" }
        assertEquals(true, titleInRange("Banana", b))
        assertEquals(true, titleInRange("The Cat", b))
        assertEquals(false, titleInRange("Apple", b))
    }

    // ---- voice matching ----

    private val books = listOf(
        BookSearchEntry("1", "The Salt Road", "Jane Doe", null),
        BookSearchEntry("2", "Salt", "John Roe", null),
        BookSearchEntry("3", "Road Trip", "Jane Doe", "Salt Series"),
        BookSearchEntry("4", "Other Book", "Jane Doe", null),
    )

    @Test
    fun `an exact title comes first, then a title that starts with the query, then the rest`() {
        assertEquals(listOf("2", "1", "3"), matchBooksForQuery("Salt", books))
    }

    @Test
    fun `every word must be found in the title, author or series`() {
        assertEquals(listOf("1"), matchBooksForQuery("the salt road", books))
        assertEquals(listOf("1", "3", "4"), matchBooksForQuery("jane doe", books))
        assertEquals(listOf("3"), matchBooksForQuery("road salt series", books))
    }

    @Test
    fun `punctuation and case are ignored`() {
        assertEquals(listOf("1"), matchBooksForQuery("THE SALT-ROAD!", books))
    }

    @Test
    fun `an empty or unmatched query matches nothing`() {
        assertEquals(emptyList(), matchBooksForQuery("", books))
        assertEquals(emptyList(), matchBooksForQuery("   ", books))
        assertEquals(emptyList(), matchBooksForQuery("zebra", books))
    }

    // ---- subtitle ----

    @Test
    fun `the subtitle joins author, progress and the connection need`() {
        assertEquals("Jane Doe", carSubtitle("Jane Doe", null, false))
        assertEquals("Jane Doe · 45% listened", carSubtitle("Jane Doe", 45, false))
        assertEquals("Jane Doe · Finished", carSubtitle("Jane Doe", 100, false))
        assertEquals("45% listened", carSubtitle(null, 45, false))
        assertEquals("Jane Doe · Needs a connection", carSubtitle("Jane Doe", 0, true))
        assertEquals("Jane Doe · 45% listened · Needs a connection", carSubtitle("Jane Doe", 45, true))
        assertEquals("", carSubtitle("  ", 0, false))
    }
}
