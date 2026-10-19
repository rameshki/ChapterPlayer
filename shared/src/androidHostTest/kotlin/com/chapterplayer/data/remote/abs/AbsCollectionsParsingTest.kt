package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.dto.AbsCollectionsResponse
import com.chapterplayer.data.remote.abs.dto.toBookCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AbsCollectionsParsingTest {

    private val page: AbsCollectionsResponse = ApiJson.decodeFromString(fixture("abs/collections-page.json"))

    @Test
    fun `parses the page header`() {
        assertEquals(3, page.total)
        assertEquals(100, page.limit)
        assertEquals(0, page.page)
        assertEquals(3, page.results.size)
    }

    @Test
    fun `parses a collection with its books and ignores the fields it does not use`() {
        val first = page.results[0]
        assertEquals("col_fixture01", first.id)
        assertEquals("Fixture Favourites", first.name)
        assertEquals(listOf("li_fixture01", "li_fixture02"), first.books.map { it.id })
    }

    @Test
    fun `a collection with an empty books list parses`() {
        assertEquals(emptyList(), page.results[1].books)
    }

    @Test
    fun `a collection without a books field gets an empty list`() {
        assertEquals("Fixture Without Books", page.results[2].name)
        assertEquals(emptyList(), page.results[2].books)
    }

    @Test
    fun `a page with only the results field still parses`() {
        val minimal: AbsCollectionsResponse = ApiJson.decodeFromString("""{"results":[{"id":"c","name":"N"}]}""")
        assertEquals(1, minimal.results.size)
        assertNull(minimal.total)
        assertNull(minimal.limit)
        assertNull(minimal.page)
    }

    @Test
    fun `maps to a book collection with prefixed book ids in the server order`() {
        assertEquals(
            BookCollection("srv1", "col_fixture01", "Fixture Favourites", listOf("abs_li_fixture01", "abs_li_fixture02")),
            page.results[0].toBookCollection("srv1"),
        )
    }

    @Test
    fun `maps a collection without books to an empty book id list`() {
        assertEquals(
            BookCollection("srv9", "col_fixture03", "Fixture Without Books", emptyList()),
            page.results[2].toBookCollection("srv9"),
        )
    }
}
