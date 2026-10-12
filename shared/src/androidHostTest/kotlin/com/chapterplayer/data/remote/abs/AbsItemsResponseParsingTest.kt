package com.chapterplayer.data.remote.abs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AbsItemsResponseParsingTest {


    // Shape taken from a real Audiobookshelf 2.37.1 response: the server sends some
    // pages without "total" (other pages of the same library include it).
    private val pageWithoutTotal = """
        {"results":[{"id":"li_a","media":{"metadata":{"title":"A","authorName":"X","narratorName":""},"coverPath":null,"duration":60.5}}],
         "limit":100,"page":6,"sortDesc":false,"mediaType":"book","minified":true,"collapseseries":false,"include":"progress","offset":600}
    """.trimIndent()

    private val pageWithTotal = pageWithoutTotal.replace("\"limit\":100", "\"total\":938,\"limit\":100")

    @Test
    fun `parses a page that has no total`() {
        val response = AbsParsers.items(pageWithoutTotal)

        assertNull(response.total)
        assertEquals(1, response.results.size)
        assertEquals(6, response.page)
    }

    @Test
    fun `parses a page that reports the total`() {
        val response = AbsParsers.items(pageWithTotal)

        assertEquals(938, response.total)
    }
}
