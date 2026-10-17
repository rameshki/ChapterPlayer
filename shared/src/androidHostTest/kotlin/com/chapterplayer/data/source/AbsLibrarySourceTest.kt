package com.chapterplayer.data.source

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
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

/** What the Audiobookshelf source turns server responses into, using the recorded fixtures. */
class AbsLibrarySourceTest {

    private val requests = mutableListOf<HttpRequestData>()

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.ABS, name = "Test", baseUrl = "http://abs.test", token = "tok", libraryId = null
    )

    private object PassThroughAuthorizer : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            call("Bearer " + server.token)
    }

    private fun source(items: String = fixture("abs/items-page-with-total.json")) = AbsLibrarySource(
        AbsApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val body = if (request.url.encodedPath == "/api/libraries") fixture("abs/libraries.json") else items
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { installApiDefaults(isDebuggable = false) }
        ),
        PassThroughAuthorizer
    )

    @Test
    fun `reports itself as the Audiobookshelf source`() {
        assertEquals(ServerSource.ABS, source().type)
    }

    @Test
    fun `uses every book library when none is configured`() = runTest {
        source().fetchBooks(server)

        val paths = requests.map { it.url.encodedPath }
        assertEquals(
            listOf("/api/libraries", "/api/libraries/lib_fixture_books/items", "/api/libraries/lib_fixture_more/items"),
            paths // the podcast library is skipped
        )
    }

    @Test
    fun `uses only the configured library`() = runTest {
        source().fetchBooks(server.copy(libraryId = "lib_fixture_more"))

        assertEquals(listOf("/api/libraries/lib_fixture_more/items"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `skips excluded libraries`() = runTest {
        source().fetchBooks(server.copy(excludedLibraryIds = setOf("lib_fixture_books")))

        assertEquals(
            listOf("/api/libraries", "/api/libraries/lib_fixture_more/items"),
            requests.map { it.url.encodedPath }
        )
    }

    @Test
    fun `a configured library is read even if listed as excluded`() = runTest {
        source().fetchBooks(server.copy(libraryId = "lib_fixture_more", excludedLibraryIds = setOf("lib_fixture_books")))

        assertEquals(listOf("/api/libraries/lib_fixture_more/items"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `excluding every book library gives an empty list`() = runTest {
        val books = source().fetchBooks(server.copy(excludedLibraryIds = setOf("lib_fixture_books", "lib_fixture_more")))

        assertEquals(emptyList(), books)
        assertEquals(listOf("/api/libraries"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `sends the bearer token on every request`() = runTest {
        source().fetchBooks(server)

        assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer tok" })
    }

    @Test
    fun `maps a book with a narrator and a cover`() = runTest {
        val book = source().fetchBooks(server.copy(libraryId = "lib_fixture_books")).first()

        assertEquals("abs_li_fixture01", book.id)
        assertEquals("Fixture Book One", book.title)
        assertEquals("Fixture Author 01", book.author)
        assertEquals("Fixture Narrator", book.narrator)
        assertEquals(94_794_084L, book.durationMs) // seconds to milliseconds, truncated
        assertEquals("http://abs.test/api/items/li_fixture01/play", book.streamUrl)
        assertEquals("http://abs.test/api/items/li_fixture01/cover", book.coverUrl)
        assertEquals(ServerSource.ABS, book.source)
        assertEquals("srv1", book.serverId)
        assertEquals(emptyList(), book.chapters) // the library list has no chapters
    }

    @Test
    fun `an unset narrator stays empty and a missing cover is null`() = runTest {
        val books = source().fetchBooks(server.copy(libraryId = "lib_fixture_books"))

        assertEquals("", books[1].narrator)
        assertNull(books[2].coverUrl)
    }

    @Test
    fun `a book with no audio has zero duration`() = runTest {
        assertEquals(0L, source().fetchBooks(server.copy(libraryId = "lib_fixture_books"))[3].durationMs)
    }

    @Test
    fun `fills in a missing title and author`() = runTest {
        val page = """{"results":[{"id":"x","media":{"metadata":{}}}],"limit":100,"page":0}"""

        val book = source(page).fetchBooks(server.copy(libraryId = "lib")).single()

        assertEquals("Unknown Title", book.title)
        assertEquals("Unknown Author", book.author)
        assertEquals("", book.narrator)
    }

    @Test
    fun `converts chapter times from seconds to milliseconds`() = runTest {
        val page = """{"results":[{"id":"x","media":{"metadata":{"title":"T"},
            "chapters":[{"id":3,"start":0.0,"end":10.5,"title":"One"},{"id":4,"start":10.5,"end":60,"title":"Two"}]}}],
            "limit":100,"page":0}"""

        val chapters = source(page).fetchBooks(server.copy(libraryId = "lib")).single().chapters

        assertEquals(listOf("One", "Two"), chapters.map { it.title })
        assertEquals(listOf(0L, 10_500L), chapters.map { it.startMs })
        assertEquals(listOf(10_500L, 60_000L), chapters.map { it.endMs })
        assertEquals(listOf(3, 4), chapters.map { it.id })
    }
}
