package com.chapterplayer.data.source

import com.chapterplayer.data.model.LibraryInfo
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Plex source turns server responses into, using the recorded fixtures. */
class PlexLibrarySourceTest {

    private val requests = mutableListOf<HttpRequestData>()

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.PLEX, name = "Test", baseUrl = "http://plex.test", token = "tok", libraryId = null
    )

    /** Answers by path: the section list, a section listing, or an item's children. */
    private fun source(
        items: String = fixture("plex/library-albums.json"),
        sections: String = fixture("plex/sections.json"),
        children: (String) -> String = { """{"MediaContainer":{"size":0}}""" },
    ) = PlexLibrarySource(
        PlexApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val path = request.url.encodedPath
                    val body = when {
                        path == "/library/sections" -> sections
                        path.endsWith("/children") -> children(path.removePrefix("/library/metadata/").removeSuffix("/children"))
                        else -> items
                    }
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
    fun `skips a turned-off section and still reads the others`() = runTest {
        val sections = """{"MediaContainer":{"size":2,"Directory":[{"key":"1","type":"artist","title":"One"},{"key":"4","type":"artist","title":"Four"}]}}"""

        source(sections = sections).fetchBooks(server.copy(excludedLibraryIds = setOf("4")))

        assertEquals(listOf("/library/sections", "/library/sections/1/all"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `reads no section when every music section is turned off`() = runTest {
        source().fetchBooks(server.copy(excludedLibraryIds = setOf("4")))

        assertEquals(listOf("/library/sections"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `a configured section is still read when another section is turned off`() = runTest {
        source().fetchBooks(server.copy(libraryId = "4", excludedLibraryIds = setOf("1")))

        assertEquals(listOf("/library/sections/4/all"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `a configured section that is turned off is not read`() = runTest {
        val books = source().fetchBooks(server.copy(libraryId = "4", excludedLibraryIds = setOf("4")))

        assertEquals(emptyList(), books)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `asks the section for its albums with the token in a header`() = runTest {
        source().fetchBooks(server.copy(libraryId = "7"))

        val request = requests.single()
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals("9", request.url.parameters["type"])
        assertNull(request.url.parameters["X-Plex-Token"])
    }

    @Test
    fun `maps each album to a book`() = runTest {
        val books = source().fetchBooks(server.copy(libraryId = "4"))

        assertEquals(listOf("plex_1013", "plex_1017", "plex_1019", "plex_1022"), books.map { it.id })
        val book = books.first()
        assertEquals("Fixture title 9", book.title)
        assertEquals("Fixture parentTitle 1", book.author)
        assertEquals("", book.narrator)
        assertEquals(0L, book.durationMs) // a section listing carries no album length; details fill it in
        assertEquals(emptyList(), book.chapters)
        assertEquals("http://plex.test/library/metadata/1013/thumb/1700000000", book.coverUrl)
        assertEquals("http://plex.test/library/metadata/1013/children", book.streamUrl)
        assertEquals(ServerSource.PLEX, book.source)
        assertEquals("srv1", book.serverId)
        assertEquals("4", book.libraryId)
        assertEquals(1_700_000_000_000L, book.addedAt)
        assertNull(book.seriesName)
    }

    @Test
    fun `no stored address carries the token`() = runTest {
        val books = source().fetchBooks(server.copy(libraryId = "4"))

        assertTrue(books.isNotEmpty())
        assertFalse(books.any { "tok" in it.streamUrl || "tok" in it.coverUrl.orEmpty() || "Token" in it.coverUrl.orEmpty() })
    }

    @Test
    fun `an album length is used when the server sends one`() = runTest {
        val page = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"9","type":"album","title":"T","parentTitle":"A","duration":3600000}]}}"""

        assertEquals(3_600_000L, source(page).fetchBooks(server.copy(libraryId = "4")).single().durationMs)
    }

    @Test
    fun `a missing added date, author or cover reads as unknown`() = runTest {
        val page = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"9","title":"T"}]}}"""

        val book = source(page).fetchBooks(server.copy(libraryId = "4")).single()
        assertEquals(0L, book.addedAt)
        assertEquals("Unknown Author", book.author)
        assertNull(book.coverUrl)
    }

    @Test
    fun `a section that lists artists is opened for their albums`() = runTest {
        // A server that ignores the album filter answers with the artists at the top level.
        val albumsOf = mapOf(
            "1003" to """{"MediaContainer":{"size":2,"Metadata":[
                {"ratingKey":"2001","type":"album","title":"Book one","parentTitle":"Writer"},
                {"ratingKey":"2002","type":"album","title":"Book two"}]}}""",
        )

        val books = source(fixture("plex/library-all-artists.json"), children = { albumsOf[it] ?: """{"MediaContainer":{"size":0}}""" })
            .fetchBooks(server.copy(libraryId = "4"))

        assertEquals(listOf("plex_2001", "plex_2002"), books.map { it.id })
        assertEquals("Writer", books[0].author)
        assertEquals("Fixture title 4", books[1].author) // no artist name on the album: the artist it was listed under
        assertEquals(6, requests.size) // the listing, then each of the five artists
        assertTrue(requests.drop(1).all { it.url.encodedPath.endsWith("/children") && it.headers["X-Plex-Token"] == "tok" })
    }

    @Test
    fun `entries that are not albums are not books`() = runTest {
        val page = """{"MediaContainer":{"size":2,"Metadata":[{"ratingKey":"1","type":"track","title":"T"},{"ratingKey":"2","type":"album","title":"A"}]}}"""

        assertEquals(listOf("plex_2"), source(page).fetchBooks(server.copy(libraryId = "4")).map { it.id })
    }

    @Test
    fun `an album listed twice is one book`() = runTest {
        val page = """{"MediaContainer":{"size":2,"Metadata":[{"ratingKey":"2","type":"album","title":"A"},{"ratingKey":"2","type":"album","title":"A"}]}}"""

        assertEquals(listOf("plex_2"), source(page).fetchBooks(server.copy(libraryId = "4")).map { it.id })
    }

    @Test
    fun `lists the music sections as libraries`() = runTest {
        assertEquals(listOf(LibraryInfo("srv1", "4", "Fixture title 3")), source().fetchLibraries(server))
    }

    @Test
    fun `a configured section is the only library listed`() = runTest {
        assertEquals(listOf(LibraryInfo("srv1", "4", "Fixture title 3")), source().fetchLibraries(server.copy(libraryId = "4")))
        assertEquals(listOf(LibraryInfo("srv1", "7", "7")), source().fetchLibraries(server.copy(libraryId = "7")))
    }

    @Test
    fun `has no collections`() = runTest {
        assertEquals(emptyList(), source().fetchCollections(server))
        assertTrue(requests.isEmpty())
    }
}
