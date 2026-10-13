package com.chapterplayer.data.source

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexApiService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Plex source turns server responses into, using the recorded fixtures. Some of
 * this pins today's behaviour even where it is a known limitation (see the roadmap).
 */
class PlexLibrarySourceTest {

    private val requests = mutableListOf<HttpRequestData>()

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.PLEX, name = "Test", baseUrl = "http://plex.test", token = "tok", libraryId = null
    )

    private fun source(items: String = fixture("plex/library-all-artists.json")) = PlexLibrarySource(
        PlexApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val body = if (request.url.encodedPath == "/library/sections") fixture("plex/sections.json") else items
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { installApiDefaults(isDebuggable = false) }
        )
    )

    @Test
    fun `reports itself as the Plex source`() {
        assertEquals(ServerSource.PLEX, source().type)
    }

    @Test
    fun `reads only the music sections when none is configured`() = runTest {
        source().fetchBooks(server)

        assertEquals(listOf("/library/sections", "/library/sections/4/all"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `uses the configured section`() = runTest {
        source().fetchBooks(server.copy(libraryId = "7"))

        assertEquals(listOf("/library/sections/7/all"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `sends the token and asks for json and chapters`() = runTest {
        source().fetchBooks(server.copy(libraryId = "7"))

        val request = requests.single()
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals("1", request.url.parameters["includeChapters"])
    }

    @Test
    fun `maps an artist entry the way the app does today`() = runTest {
        // The section call returns artists: no author, duration or media, so the book has none either.
        val books = source().fetchBooks(server.copy(libraryId = "4"))

        assertEquals(5, books.size)
        val book = books.first()
        assertEquals("plex_1003", book.id)
        assertEquals("Fixture title 4", book.title)
        assertEquals("Unknown Author", book.author)
        assertEquals("", book.narrator)
        assertEquals(0L, book.durationMs)
        assertEquals("http://plex.test?X-Plex-Token=tok", book.streamUrl) // no media part, so no stream path
        assertNull(book.coverUrl)
        assertEquals(ServerSource.PLEX, book.source)
        assertEquals("srv1", book.serverId)
    }

    @Test
    fun `builds the cover address from the thumbnail path and the token`() = runTest {
        val covers = source().fetchBooks(server.copy(libraryId = "4")).map { it.coverUrl }

        assertEquals(listOf(false, false, true, false, false), covers.map { it != null })
        val cover = covers.filterNotNull().single()
        assertTrue(cover.startsWith("http://plex.test/library/metadata/") && cover.endsWith("?X-Plex-Token=tok"))
    }

    @Test
    fun `maps a track with chapters`() = runTest {
        val book = source(fixture("plex/track-with-chapters.json")).fetchBooks(server.copy(libraryId = "4")).single()

        assertEquals("plex_1024", book.id)
        assertEquals("Fixture parentTitle 3", book.author)
        assertEquals(47_198_808L, book.durationMs)
        assertEquals("http://plex.test/library/parts/1027/1700000000/file.m4b?X-Plex-Token=tok", book.streamUrl)
        assertEquals(24, book.chapters.size)
        val first = book.chapters.first()
        assertEquals(1029, first.id)
        assertEquals("Fixture tag 6", first.title)
        assertEquals(0L, first.startMs)
        assertEquals(0L, first.endMs) // Plex chapters carry no end time in the app today
        assertEquals(47_121_400L, book.chapters.last().startMs)
    }
}
