package com.chapterplayer.data.remote

import com.chapterplayer.data.remote.abs.AbsParsers
import com.chapterplayer.data.remote.plex.PlexParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * Edge cases in how the current parsers read JSON that real servers send, so the library
 * swap cannot change them unnoticed.
 */
class LenientParsingTest {

    private fun absPage(item: String) =
        """{"results":[$item],"total":1,"limit":100,"page":0}"""

    private fun plexItems(metadata: String) =
        """{"MediaContainer":{"size":1,"Metadata":[$metadata]}}"""

    @Test
    fun `a book with chapters in the response parses them`() {
        val item = """{"id":"li_1","media":{"metadata":{"title":"A"},"duration":60,
            "chapters":[{"id":0,"start":0.0,"end":10.5,"title":"One"},{"id":1,"start":10.5,"end":60,"title":"Two"}]}}"""

        val chapters = AbsParsers.items(absPage(item)).results.single().media.chapters

        assertEquals(listOf("One", "Two"), chapters?.map { it.title })
        assertEquals(listOf(10.5, 60.0), chapters?.map { it.end })
    }

    @Test
    fun `a whole-number duration reads as a decimal`() {
        val item = """{"id":"li_1","media":{"metadata":{"title":"A"},"duration":60}}"""

        assertEquals(60.0, AbsParsers.items(absPage(item)).results.single().media.duration)
    }

    @Test
    fun `missing optional fields read as null`() {
        val item = """{"id":"li_1","media":{"metadata":{}}}"""

        val media = AbsParsers.items(absPage(item)).results.single().media
        assertNull(media.metadata.title)
        assertNull(media.metadata.authorName)
        assertNull(media.metadata.narratorName)
        assertNull(media.duration)
        assertNull(media.coverPath)
        assertNull(media.chapters)
    }

    @Test
    fun `a book without an id is rejected`() {
        assertFails { AbsParsers.items(absPage("""{"media":{"metadata":{}}}""")) }
    }

    @Test
    fun `a plex id sent as a number reads as text`() {
        val item = PlexParsers.libraryItems(plexItems("""{"ratingKey":205924,"title":"T"}""")).mediaContainer.metadata!!.single()

        assertEquals("205924", item.ratingKey)
    }

    @Test
    fun `a library with no items has no metadata list`() {
        val response = PlexParsers.libraryItems("""{"MediaContainer":{"size":0}}""")

        assertNull(response.mediaContainer.metadata)
    }

    @Test
    fun `sections with no directories have no directory list`() {
        val response = PlexParsers.sections("""{"MediaContainer":{"size":0}}""")

        assertNull(response.mediaContainer.directories)
    }

    @Test
    fun `plex ignores fields it does not know`() {
        val item = PlexParsers.libraryItems(plexItems("""{"ratingKey":"1","title":"T","somethingNew":{"a":[1,2]}}""")).mediaContainer.metadata!!.single()

        assertEquals("T", item.title)
    }
}
