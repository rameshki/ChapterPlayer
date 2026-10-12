package com.chapterplayer.data.remote.abs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Parses responses recorded from a real Audiobookshelf 2.37.1 server (text, paths and ids
 * replaced with synthetic values, structure and number ranges kept).
 */
class AbsFixtureParsingTest {

    private val page = AbsParsers.items(fixture("abs/items-page-with-total.json"))

    @Test
    fun `parses the libraries list`() {
        val libraries = AbsParsers.libraries(fixture("abs/libraries.json")).libraries

        assertEquals(listOf("lib_fixture_books", "lib_fixture_more", "lib_fixture_pods"), libraries.map { it.id })
        assertEquals(listOf("Fixture Books", "Fixture More Books", "Fixture Podcasts"), libraries.map { it.name })
        assertEquals(listOf("book", "book", "podcast"), libraries.map { it.mediaType })
    }

    @Test
    fun `parses the page header`() {
        assertEquals(250, page.total)
        assertEquals(100, page.limit)
        assertEquals(0, page.page)
        assertEquals(7, page.results.size)
    }

    @Test
    fun `parses a book with a narrator and a cover`() {
        val item = page.results[0]

        assertEquals("li_fixture01", item.id)
        assertEquals("Fixture Book One", item.media.metadata.title)
        assertEquals("Fixture Author 01", item.media.metadata.authorName)
        assertEquals("Fixture Narrator", item.media.metadata.narratorName)
        assertEquals(94794.084989, item.media.duration)
        assertNotNull(item.media.coverPath)
    }

    @Test
    fun `the list response carries no chapters`() {
        // The server sends numChapters but not the chapters themselves in the library list.
        assertEquals(true, page.results.all { it.media.chapters == null })
    }

    @Test
    fun `an unset narrator arrives as an empty string`() {
        assertEquals("", page.results[1].media.metadata.narratorName)
    }

    @Test
    fun `a book without a cover has a null cover path`() {
        assertNull(page.results[2].media.coverPath)
    }

    @Test
    fun `a book with no audio has zero duration`() {
        assertEquals(0.0, page.results[3].media.duration)
    }

    @Test
    fun `keeps apostrophes and ampersands in text`() {
        val metadata = page.results[4].media.metadata

        assertEquals("Fixture's Book & Friends", metadata.title)
        assertEquals("Fixture Author & Co", metadata.authorName)
    }

    @Test
    fun `keeps non-ASCII text`() {
        assertEquals("Café Møn – Épisode 1 (L’Été)", page.results[6].media.metadata.title)
    }

    @Test
    fun `parses a page the server sent without a total`() {
        val withoutTotal = AbsParsers.items(fixture("abs/items-page-without-total.json"))

        assertNull(withoutTotal.total)
        assertEquals(6, withoutTotal.page)
        assertEquals(3, withoutTotal.results.size)
    }
}
