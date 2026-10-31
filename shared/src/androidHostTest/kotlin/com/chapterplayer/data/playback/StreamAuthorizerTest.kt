package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import com.chapterplayer.testing.FakeServerList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS player's token path: which header goes with which address, and what a refusal means (Plex and ABS). */
class StreamAuthorizerTest {

    private val plex = ServerConfig(
        id = "p", source = ServerSource.PLEX, name = "Plex", baseUrl = "https://plex.example.test:32400", token = "plex-token",
    )
    private val track = "https://plex.example.test:32400/library/parts/11/22/file.mp3"

    private class Seen(val url: String, val plexToken: String?, val auth: String?, val range: String?)

    private fun authorizer(calls: MutableList<String>) = object : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T {
            calls += server.id
            return call("Bearer ${server.token}")
        }
    }

    private fun client(seen: MutableList<Seen>, status: HttpStatusCode) = HttpClient(MockEngine { request ->
        seen += Seen(request.url.toString(), request.headers[PLEX_TOKEN_HEADER], request.headers[HttpHeaders.Authorization], request.headers[HttpHeaders.Range])
        if (status.value < 400) respond("", status) else respondError(status)
    }) {
        followRedirects = false
        expectSuccess = false
    }

    @Test
    fun `a Plex track gets the X-Plex-Token header after one small check - never a bearer and never in the address`() = runTest {
        val seen = mutableListOf<Seen>()
        val absCalls = mutableListOf<String>()
        val streams = StreamAuthorizer(FakeServerList(listOf(plex)), authorizer(absCalls), client(seen, HttpStatusCode.PartialContent))
        val headers = streams.prepare(track)
        assertEquals(mapOf(PLEX_TOKEN_HEADER to "plex-token"), headers.values)
        assertEquals(1, seen.size)
        assertEquals(track, seen[0].url)
        assertEquals("plex-token", seen[0].plexToken)
        assertNull(seen[0].auth)
        assertEquals("bytes=0-0", seen[0].range)
        assertTrue(absCalls.isEmpty())
    }

    @Test
    fun `a Plex track whose token is refused says sign in again with no refresh and blanks the token`() = runTest {
        val seen = mutableListOf<Seen>()
        val absCalls = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val streams = StreamAuthorizer(
            FakeServerList(listOf(plex)), authorizer(absCalls), client(seen, HttpStatusCode.Unauthorized),
            onPlexRefused = { refused += it.id },
        )
        val e = assertFailsWith<PlaybackStartException> { streams.prepare(track) }
        assertEquals(SIGN_IN_AGAIN_TEXT, e.message)
        assertEquals(1, seen.size)
        assertTrue(absCalls.isEmpty())
        assertEquals(listOf("p"), refused)
    }

    @Test
    fun `a 403 is not a token to refresh - it says the account may not play it`() = runTest {
        val refused = mutableListOf<String>()
        val abs = ServerConfig(id = "a", source = ServerSource.ABS, name = "ABS", baseUrl = "https://abs.example.test", token = "abs-token")
        val forbidden = StreamAuthorizer(
            FakeServerList(listOf(abs, plex)), authorizer(mutableListOf()), client(mutableListOf(), HttpStatusCode.Forbidden),
            onPlexRefused = { refused += it.id },
        )
        assertEquals(NOT_ALLOWED_TEXT, assertFailsWith<PlaybackStartException> { forbidden.prepare(track) }.message)
        assertEquals(NOT_ALLOWED_TEXT, assertFailsWith<PlaybackStartException> { forbidden.prepare("https://abs.example.test/api/items/1/file/2") }.message)
        assertTrue(refused.isEmpty())
    }

    @Test
    fun `the current headers come without a check - Plex token or a fresh bearer`() = runTest {
        val seen = mutableListOf<Seen>()
        val abs = ServerConfig(id = "a", source = ServerSource.ABS, name = "ABS", baseUrl = "https://abs.example.test", token = "abs-token")
        val streams = StreamAuthorizer(FakeServerList(listOf(abs, plex)), authorizer(mutableListOf()), client(seen, HttpStatusCode.OK))
        assertEquals(mapOf(PLEX_TOKEN_HEADER to "plex-token"), streams.currentHeaders(track)?.values)
        assertEquals(mapOf(HttpHeaders.Authorization to "Bearer abs-token"), streams.currentHeaders("https://abs.example.test/x")?.values)
        assertNull(streams.currentHeaders("https://other.example.test/x"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a Plex server signed out has no token to send - the check goes without one and says sign in again`() = runTest {
        val seen = mutableListOf<Seen>()
        val streams = StreamAuthorizer(FakeServerList(listOf(plex.copy(token = ""))), authorizer(mutableListOf()), client(seen, HttpStatusCode.Unauthorized))
        val e = assertFailsWith<PlaybackStartException> { streams.prepare(track) }
        assertEquals(SIGN_IN_AGAIN_TEXT, e.message)
        assertNull(seen.single().plexToken)
    }

    @Test
    fun `an Audiobookshelf file goes through the authorizer and gets the bearer`() = runTest {
        val abs = ServerConfig(id = "a", source = ServerSource.ABS, name = "ABS", baseUrl = "https://abs.example.test", token = "abs-token")
        val seen = mutableListOf<Seen>()
        val absCalls = mutableListOf<String>()
        val streams = StreamAuthorizer(FakeServerList(listOf(abs, plex)), authorizer(absCalls), client(seen, HttpStatusCode.OK))
        val headers = streams.prepare("https://abs.example.test/api/items/1/file/2")
        assertEquals(mapOf(HttpHeaders.Authorization to "Bearer abs-token"), headers.values)
        assertEquals(listOf("a"), absCalls)
        assertNull(seen.single().plexToken)
    }

    @Test
    fun `files on the phone need no header and no check`() = runTest {
        val seen = mutableListOf<Seen>()
        val streams = StreamAuthorizer(FakeServerList(listOf(plex)), authorizer(mutableListOf()), client(seen, HttpStatusCode.OK))
        assertTrue(streams.prepare("file:///var/mobile/book/1.mp3").isEmpty)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `missing audio and an unreachable server have their own words`() = runTest {
        val notFound = StreamAuthorizer(FakeServerList(listOf(plex)), authorizer(mutableListOf()), client(mutableListOf(), HttpStatusCode.NotFound))
        assertEquals("The audio was not found on the server", assertFailsWith<PlaybackStartException> { notFound.prepare(track) }.message)
        val offline = StreamAuthorizer(
            FakeServerList(listOf(plex)), authorizer(mutableListOf()),
            HttpClient(MockEngine { throw kotlinx.io.IOException("no route") }) { expectSuccess = false; followRedirects = false },
        )
        assertEquals("Cannot reach the server", assertFailsWith<PlaybackStartException> { offline.prepare(track) }.message)
    }
}
