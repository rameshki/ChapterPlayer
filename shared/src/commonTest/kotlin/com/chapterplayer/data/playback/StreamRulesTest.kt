package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The pure rules of the iOS stream check: redirects, answers, words and which server an address belongs to. */
class StreamRulesTest {

    private val base = "https://books.example.test"

    @Test
    fun `a redirect with a token is followed only to the same scheme host and port`() {
        assertEquals("$base/other/file", streamRedirectTarget("$base/a/file", "/other/file", 1, withToken = true))
        assertEquals("$base/a/next", streamRedirectTarget("$base/a/file", "next", 1, withToken = true))
        assertNull(streamRedirectTarget("$base/a/file", "https://cdn.example.test/file", 1, withToken = true))
        assertNull(streamRedirectTarget("$base/a/file", "https://books.example.test:8443/file", 1, withToken = true))
        // Without a token another host is fine (nothing secret travels).
        assertEquals("https://cdn.example.test/file", streamRedirectTarget("$base/a/file", "https://cdn.example.test/file", 1, withToken = false))
    }

    @Test
    fun `a redirect never goes from https to http or to another scheme and stops after five hops`() {
        assertNull(streamRedirectTarget("$base/a", "http://books.example.test/a", 1, withToken = false))
        assertNull(streamRedirectTarget("$base/a", "ftp://books.example.test/a", 1, withToken = false))
        assertEquals("$base/b", streamRedirectTarget("$base/a", "/b", MAX_STREAM_REDIRECTS, withToken = true))
        assertNull(streamRedirectTarget("$base/a", "/b", MAX_STREAM_REDIRECTS + 1, withToken = true))
        // Plain http to https on the same host is another scheme: refused with a token, fine without.
        assertNull(streamRedirectTarget("http://nas.example.test/a", "https://nas.example.test/a", 1, withToken = true))
        assertEquals("https://nas.example.test/a", streamRedirectTarget("http://nas.example.test/a", "https://nas.example.test/a", 1, withToken = false))
    }

    @Test
    fun `answers mean audio - a refused token - not found - a redirect or a failure`() {
        assertEquals(StreamStep.Done(StreamCheck.OK), streamStep(206, null, "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.OK), streamStep(200, null, "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.UNAUTHORIZED), streamStep(401, null, "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.UNAUTHORIZED), streamStep(403, null, "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.NOT_FOUND), streamStep(404, null, "$base/a", 0, true))
        assertEquals(StreamStep.Follow("$base/b"), streamStep(302, "/b", "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.MOVED_AWAY), streamStep(302, "https://elsewhere.example.test/b", "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.FAILED), streamStep(302, null, "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.FAILED), streamStep(500, null, "$base/a", 0, true))
        assertEquals(StreamStep.Done(StreamCheck.MOVED_AWAY), streamStep(302, "/b", "$base/a", MAX_STREAM_REDIRECTS, true))
    }

    @Test
    fun `the words for a stopped stream never hold an address`() {
        assertNull(streamCheckText(StreamCheck.OK))
        assertEquals(SIGN_IN_AGAIN_TEXT, streamCheckText(StreamCheck.UNAUTHORIZED))
        StreamCheck.entries.mapNotNull(::streamCheckText).forEach { assertFalse("://" in it) }
    }

    @Test
    fun `the headers never show their values`() {
        val headers = StreamHeaders(mapOf("Authorization" to "Bearer secret-value"))
        assertFalse("secret-value" in headers.toString())
        assertTrue(StreamHeaders.None.isEmpty)
    }

    @Test
    fun `an address belongs to the server it sits under - Plex connections included`() {
        val abs = ServerConfig(id = "a", name = "ABS", baseUrl = "$base/abs", token = "t1", source = ServerSource.ABS)
        val plex = ServerConfig(
            id = "p", name = "Plex", baseUrl = "https://plex.example.test:32400", token = "t2", source = ServerSource.PLEX,
            alternateBaseUrls = listOf("http://192.0.2.10:32400"),
        )
        val servers = listOf(abs, plex)
        assertSame(abs, streamServerFor("$base/abs/api/items/1/file/2", servers))
        assertNull(streamServerFor("$base/absent/file", servers))
        assertSame(plex, streamServerFor("https://plex.example.test:32400/library/parts/1/2/file.mp3", servers))
        assertSame(plex, streamServerFor("http://192.0.2.10:32400/library/parts/1/2/file.mp3", servers))
        assertNull(streamServerFor("http://192.0.2.10:3240/library/parts/1", servers))
        assertNull(streamServerFor("$base/abs/file", listOf(abs.copy(token = ""))))
    }

    @Test
    fun `Apple errors pick the words - HTTP status first`() {
        val net = PlatformErrorCode("NSURLErrorDomain", -1009L)
        val other = PlatformErrorCode("AVFoundationErrorDomain", -11800L)
        assertEquals(EngineFailure.UNAUTHORIZED, classifyAppleMediaError(listOf(net), 401))
        assertEquals(EngineFailure.NOT_FOUND, classifyAppleMediaError(listOf(other), 404))
        assertEquals(EngineFailure.NETWORK, classifyAppleMediaError(listOf(other, net), null))
        assertEquals(EngineFailure.UNAUTHORIZED, classifyAppleMediaError(listOf(PlatformErrorCode("NSURLErrorDomain", -1013L)), null))
        assertEquals(EngineFailure.NOT_FOUND, classifyAppleMediaError(listOf(other, PlatformErrorCode("CoreMediaErrorDomain", -12938L)), null))
        assertEquals(EngineFailure.OTHER, classifyAppleMediaError(listOf(other), null))
        assertEquals(EngineFailure.OTHER, classifyAppleMediaError(emptyList(), 500))
    }
}
