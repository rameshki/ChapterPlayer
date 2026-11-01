package com.chapterplayer.data.downloads

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The redirect resolver against java.net.URI.resolve, which the downloader used before it was common code. */
class RedirectResolveTest {

    private val base = "http://abs.test/api/items/li_1/file/77/download"

    private fun old(base: String, location: String): String = URI(base).resolve(location.trim()).toString()

    @Test
    fun `agrees with java net URI for the usual locations`() {
        val locations = listOf(
            "/files/77", "files/77", "../x", "../../y/z", "./x?a=1#f", "x?y=1&z=2", "#frag", "/p?q=1#f",
            "http://other.test/f", "https://abs.test:8443/a/b", "//other.test/f", "  /trimmed  ", "a/./b/../c",
            "/%7Euser/file", "file/ünïcode",
        )
        for (location in locations) assertEquals(old(base, location), resolve(base, location), "Location $location")
        val withPort = "http://abs.test:13378/a/b"
        for (location in listOf("c", "/c", "../c")) {
            assertEquals(old(withPort, location), resolve(withPort, location), "Location $location")
        }
    }

    @Test
    fun `a base without a path takes a slash before a relative location`() {
        assertEquals("http://abs.test/x", resolve("http://abs.test", "x"))
    }

    @Test
    fun `a query-only location keeps the base path as the RFC says`() {
        // java.net.URI dropped the last segment here (a known departure from RFC 3986); the origin is the same either way.
        assertEquals("http://abs.test/api/items/li_1/file/77/download?token=x", resolve(base, "?token=x"))
    }

    @Test
    fun `dot segments are removed everywhere and never climb above the root`() {
        assertEquals("http://abs.test/b", resolve(base, "/a/../b"))
        assertEquals("http://abs.test/x", resolve("http://abs.test/a/", "../../../x"))
        assertEquals("http://other.test/b", resolve(base, "http://other.test/a/../b"))
    }

    @Test
    fun `what java net URI refused is not followed`() {
        assertNull(resolve(base, "a b"))
        assertNull(resolve(base, "x|y"))
        assertNull(resolve(base, ""))
        assertNull(resolve("not absolute", "x"))
    }

    @Test
    fun `remove dot segments follows the RFC examples`() {
        assertEquals("/a/g", removeDotSegments("/a/b/c/./../../g"))
        assertEquals("mid/6", removeDotSegments("mid/content=5/../6"))
    }
}
