package com.chapterplayer.domain.covers

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverRulesTest {

    private val authority = "com.chapterplayer.covers"

    // ---- size ----

    @Test
    fun `a small cover is decoded whole and kept at its size`() {
        assertEquals(1, coverSampleSize(400, 300))
        assertEquals(CoverSize(400, 300), coverTargetSize(400, 300))
        assertEquals(CoverSize(512, 512), coverTargetSize(512, 512))
    }

    @Test
    fun `a big cover is sampled by a power of two that keeps it at least the target size`() {
        assertEquals(2, coverSampleSize(1024, 1024))
        assertEquals(2, coverSampleSize(1500, 1500))
        assertEquals(4, coverSampleSize(2048, 1000))
        assertEquals(8, coverSampleSize(4096, 4096))
        assertEquals(1, coverSampleSize(1000, 1000))
    }

    @Test
    fun `the long side is brought to 512 and the shape kept`() {
        assertEquals(CoverSize(512, 512), coverTargetSize(1500, 1500))
        assertEquals(CoverSize(512, 256), coverTargetSize(2000, 1000))
        assertEquals(CoverSize(341, 512), coverTargetSize(1000, 1500))
        assertEquals(CoverSize(512, 1), coverTargetSize(5000, 3))
    }

    @Test
    fun `sizes that are not an image give nothing`() {
        assertNull(coverTargetSize(0, 100))
        assertNull(coverTargetSize(100, -1))
        assertEquals(1, coverSampleSize(0, 0))
    }

    // ---- book ids ----

    @Test
    fun `book ids of both servers are cover ids`() {
        assertTrue(isCoverBookId("abs_li_abc123"))
        assertTrue(isCoverBookId("abs_7f0c2d0e-1b2a-4c3d-9e8f-001122334455"))
        assertTrue(isCoverBookId("plex_12345"))
    }

    @Test
    fun `anything else is not a cover id`() {
        assertFalse(isCoverBookId(""))
        assertFalse(isCoverBookId("abs_"))
        assertFalse(isCoverBookId("plex_"))
        assertFalse(isCoverBookId("car:continue"))
        assertFalse(isCoverBookId("other_1"))
        assertFalse(isCoverBookId("abs_.."))
        assertFalse(isCoverBookId("abs_a/b"))
        assertFalse(isCoverBookId("abs_a%2Fb"))
        assertFalse(isCoverBookId("abs_a b"))
        assertFalse(isCoverBookId("abs_" + "x".repeat(200)))
    }

    // ---- content uris ----

    @Test
    fun `the cover address names the authority and the book only`() {
        assertEquals("com.chapterplayer.covers", coverAuthority("com.chapterplayer"))
        assertEquals("com.chapterplayer.debug.covers", coverAuthority("com.chapterplayer.debug"))
        assertEquals("content://com.chapterplayer.covers/cover/abs_li_1", coverContentUri(authority, "abs_li_1"))
        assertEquals("content://com.chapterplayer.covers/cover/plex_9", coverContentUri(authority, "plex_9"))
    }

    @Test
    fun `no address is made for an id that is not a book`() {
        assertNull(coverContentUri(authority, "car:continue"))
        assertNull(coverContentUri(authority, "abs_../x"))
        assertNull(coverContentUri(authority, ""))
    }

    @Test
    fun `an address is read back to its book`() {
        assertEquals("abs_li_1", parseCoverUri("content://com.chapterplayer.covers/cover/abs_li_1", authority))
        assertEquals("plex_9", parseCoverUri(coverContentUri(authority, "plex_9")!!, authority))
    }

    @Test
    fun `other authorities and look-alikes are refused`() {
        assertNull(parseCoverUri("content://com.chapterplayer.debug.covers/cover/abs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers.evil/cover/abs_1", authority))
        assertNull(parseCoverUri("content://evil.com.chapterplayer.covers/cover/abs_1", authority))
        assertNull(parseCoverUri("content://user@com.chapterplayer.covers/cover/abs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers:80/cover/abs_1", authority))
        assertNull(parseCoverUri("file://com.chapterplayer.covers/cover/abs_1", authority))
        assertNull(parseCoverUri("https://com.chapterplayer.covers/cover/abs_1", authority))
        assertNull(parseCoverUri("com.chapterplayer.covers/cover/abs_1", authority))
    }

    @Test
    fun `path tricks are refused`() {
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/../cover/abs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/abs_1/..", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/..%2Fabs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/abs_1%2F..%2Fx", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover%2Fabs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/abs_1/", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers//cover/abs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/Cover/abs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/files/abs_1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/abs_1?x=1", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/abs_1#x", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/abs_1\u0000", authority))
        assertNull(parseCoverUri("content://com.chapterplayer.covers/cover/car:continue", authority))
    }

    // ---- callers ----

    @Test
    fun `this app and the car packages may read covers`() {
        assertTrue(coverCallerAllowed(listOf("com.chapterplayer"), "com.chapterplayer"))
        assertTrue(coverCallerAllowed(listOf("com.google.android.projection.gearhead"), "com.chapterplayer"))
        assertTrue(coverCallerAllowed(listOf("com.android.systemui"), "com.chapterplayer"))
        assertTrue(coverCallerAllowed(listOf("com.google.android.autosimulator"), "com.chapterplayer"))
    }

    @Test
    fun `everyone else is refused, look-alikes included`() {
        assertFalse(coverCallerAllowed(null, "com.chapterplayer"))
        assertFalse(coverCallerAllowed(emptyList(), "com.chapterplayer"))
        assertFalse(coverCallerAllowed(listOf("com.example.other"), "com.chapterplayer"))
        assertFalse(coverCallerAllowed(listOf("com.google.android.projection.gearhead.evil"), "com.chapterplayer"))
        assertFalse(coverCallerAllowed(listOf("com.google.android.projection"), "com.chapterplayer"))
        assertFalse(coverCallerAllowed(listOf("Com.Google.Android.Projection.Gearhead"), "com.chapterplayer"))
        assertFalse(coverCallerAllowed(listOf("com.chapterplayer.debug"), "com.chapterplayer"))
        assertFalse(coverCallerAllowed(listOf("com.chapterplayer"), "com.chapterplayer.debug"))
        assertFalse(coverCallerAllowed(listOf(""), ""))
    }

    @Test
    fun `a shared user id is allowed only when one of its packages is trusted`() {
        assertTrue(coverCallerAllowed(listOf("com.example.a", "com.google.android.projection.gearhead"), "com.chapterplayer"))
        assertFalse(coverCallerAllowed(listOf("com.example.a", "com.example.b"), "com.chapterplayer"))
    }

    // ---- which request a cover becomes ----

    private fun server(source: ServerSource, base: String, token: String = "secret", alternates: List<String> = emptyList()) =
        ServerConfig(id = "s1", name = "Home", baseUrl = base, token = token, source = source, alternateBaseUrls = alternates)

    @Test
    fun `an Audiobookshelf cover on its server gets the bearer`() {
        val abs = server(ServerSource.ABS, "https://books.example")
        assertEquals(CoverAuth.ABS_BEARER, coverRequestAuth("https://books.example/api/items/1/cover", abs))
    }

    @Test
    fun `an Audiobookshelf server with no token is asked without one`() {
        val abs = server(ServerSource.ABS, "https://books.example", token = "")
        assertEquals(CoverAuth.NONE, coverRequestAuth("https://books.example/api/items/1/cover", abs))
    }

    @Test
    fun `a Plex cover on any of its server's addresses gets the token header`() {
        val plex = server(ServerSource.PLEX, "https://10-0-0-2.abc.plex.direct:32400", alternates = listOf("https://remote.example:32400"))
        assertEquals(CoverAuth.PLEX_TOKEN, coverRequestAuth("https://10-0-0-2.abc.plex.direct:32400/library/metadata/1/thumb/2", plex))
        assertEquals(CoverAuth.PLEX_TOKEN, coverRequestAuth("https://remote.example:32400/library/metadata/1/thumb/2", plex))
    }

    @Test
    fun `a cover elsewhere is never asked for`() {
        val abs = server(ServerSource.ABS, "https://books.example")
        assertNull(coverRequestAuth("https://other.example/api/items/1/cover", abs))
        assertNull(coverRequestAuth("https://books.example.evil/api/items/1/cover", abs))
        assertNull(coverRequestAuth("https://books.example:8443/api/items/1/cover", abs))
        assertNull(coverRequestAuth("http://books.example/api/items/1/cover", abs))
        assertNull(coverRequestAuth("https://books.example", abs))
        val plex = server(ServerSource.PLEX, "http://10.0.0.2:32400")
        assertNull(coverRequestAuth("http://10.0.0.2:3240/library/metadata/1/thumb/2", plex))
        assertNull(coverRequestAuth("http://10.0.0.2:324000/library/metadata/1/thumb/2", plex))
        assertNull(coverRequestAuth("https://other.example/library/metadata/1/thumb/2", plex))
    }

    @Test
    fun `a Plex server without a token is not asked`() {
        val plex = server(ServerSource.PLEX, "http://10.0.0.2:32400", token = "")
        assertNull(coverRequestAuth("http://10.0.0.2:32400/library/metadata/1/thumb/2", plex))
    }

    @Test
    fun `a base address with a path keeps covers under that path`() {
        val abs = server(ServerSource.ABS, "https://example.com/abs/")
        assertEquals(CoverAuth.ABS_BEARER, coverRequestAuth("https://example.com/abs/api/items/1/cover", abs))
        assertNull(coverRequestAuth("https://example.com/absolutely/api/items/1/cover", abs))
        assertNull(coverRequestAuth("https://example.com/other/api/items/1/cover", abs))
    }
}
