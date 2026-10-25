package com.chapterplayer.data.source

import com.chapterplayer.testing.TestPlexClientId
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Plex source turns server responses into, using the recorded fixtures. */
class PlexLibrarySourceTest {

    // Track details are read side by side, so the list is shared between threads.
    private val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.PLEX, name = "Test", baseUrl = "http://plex.test", token = "tok", libraryId = null
    )

    /** Answers by path: the section list, a section listing, or an item's children. */
    private fun source(
        // The recorded page is the first 4 of 942; here the section holds just those 4.
        items: String = fixture("plex/library-albums.json").replace("\"totalSize\": 942", "\"totalSize\": 4"),
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
            ) { installApiDefaults(isDebuggable = false) }, TestPlexClientId
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
    fun `a server with no music library fails the sync with words, never an empty library`() = runTest {
        val noMusic = """{"MediaContainer":{"size":1,"Directory":[{"key":"1","type":"movie","title":"Films"}]}}"""

        val error = kotlin.test.assertFailsWith<IllegalStateException> { source(sections = noMusic).fetchSnapshot(server) }

        assertEquals(com.chapterplayer.domain.plex.PLEX_NO_MUSIC_LIBRARY_TEXT, error.message)
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

    private val twoSections =
        """{"MediaContainer":{"size":2,"Directory":[{"key":"1","type":"artist","title":"Talks"},{"key":"4","type":"artist","title":"Books"}]}}"""

    /** The section list, and each section's albums, with [failing] sections answering 500. */
    private fun syncSource(failing: Set<String>, pages: (String, Int) -> String = { key, _ ->
        """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"$key-a","type":"album","title":"T"}]}}"""
    }) = PlexLibrarySource(
        PlexApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val path = request.url.encodedPath
                    val key = path.removePrefix("/library/sections/").removeSuffix("/all")
                    when {
                        path == "/library/sections" -> respond(twoSections, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        key in failing -> respond("", HttpStatusCode.InternalServerError)
                        else -> respond(
                            pages(key, request.url.parameters["X-Plex-Container-Start"]!!.toInt()),
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            ) { installApiDefaults(isDebuggable = false) }, TestPlexClientId
        )
    )

    @Test
    fun `a sync reads the section list once and lists the libraries`() = runTest {
        val snapshot = syncSource(failing = emptySet()).fetchSnapshot(server)

        assertEquals(listOf("/library/sections", "/library/sections/1/all", "/library/sections/4/all"), requests.map { it.url.encodedPath })
        assertEquals(listOf("plex_1-a", "plex_4-a"), snapshot.books.map { it.id })
        assertEquals(listOf(LibraryInfo("srv1", "1", "Talks"), LibraryInfo("srv1", "4", "Books")), snapshot.libraries)
        assertEquals(emptyList(), snapshot.collections)
        assertEquals(emptyMap(), snapshot.failedLibraries)
    }

    @Test
    fun `one failing section does not fail the others`() = runTest {
        val snapshot = syncSource(failing = setOf("1")).fetchSnapshot(server)

        assertEquals(listOf("plex_4-a"), snapshot.books.map { it.id })
        assertEquals(mapOf("1" to "Talks: HTTP 500 Internal Server Error"), snapshot.failedLibraries)
        assertFalse(snapshot.failedLibraries.values.any { "tok" in it || "plex.test" in it })
    }

    @Test
    fun `every section failing fails the sync`() = runTest {
        assertFailsWith<HttpStatusException> { syncSource(failing = setOf("1", "4")).fetchSnapshot(server) }
    }

    @Test
    fun `a failing section list fails the sync when no section is configured`() = runTest {
        val source = PlexLibrarySource(
            PlexApiService(HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) { installApiDefaults(isDebuggable = false) }, TestPlexClientId)
        )

        assertFailsWith<HttpStatusException> { source.fetchSnapshot(server) }
    }

    @Test
    fun `a configured section syncs even when the section list fails`() = runTest {
        val source = PlexLibrarySource(
            PlexApiService(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/library/sections") respond("", HttpStatusCode.InternalServerError)
                        else respond(fixture("plex/library-albums.json"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                ) { installApiDefaults(isDebuggable = false) }, TestPlexClientId
            )
        )

        val snapshot = source.fetchSnapshot(server.copy(libraryId = "4"))

        assertEquals(4, snapshot.books.size)
        assertNull(snapshot.libraries)
    }

    @Test
    fun `a large section is read page by page`() = runTest {
        // 450 albums in pages of 200: three requests.
        val snapshot = syncSource(failing = emptySet(), pages = { key, start ->
            val end = minOf(start + 200, 450)
            val items = (start until end).joinToString(",") { """{"ratingKey":"$key-$it","type":"album","title":"T"}""" }
            """{"MediaContainer":{"size":${end - start},"totalSize":450,"offset":$start,"Metadata":[$items]}}"""
        }).fetchSnapshot(server.copy(libraryId = "4"))

        assertEquals(450, snapshot.books.size)
        val starts = requests.filter { it.url.encodedPath == "/library/sections/4/all" }.map { it.url.parameters["X-Plex-Container-Start"] }
        assertEquals(listOf("0", "200", "400"), starts)
        assertTrue(requests.all { it.url.parameters["X-Plex-Container-Size"] == null || it.url.parameters["X-Plex-Container-Size"] == "200" })
    }

    /** A server holding [album]'s metadata, its [tracks] list and each track's detail by rating key. */
    private fun detailsSource(album: String, tracks: String, trackDetails: Map<String, String>) = PlexLibrarySource(
        PlexApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val path = request.url.encodedPath
                    val key = path.removePrefix("/library/metadata/").removeSuffix("/children")
                    val body = when {
                        path.endsWith("/children") -> tracks
                        key in trackDetails -> trackDetails.getValue(key)
                        else -> album
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { installApiDefaults(isDebuggable = false) }, TestPlexClientId
        )
    )

    private val albumDetail = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1019","type":"album","title":"T",
        "summary":"About the book","year":2024,"Genre":[{"tag":"Fantasy"},{"tag":""}]}]}}"""

    @Test
    fun `details of a one-file book read the album, its track and the track's chapters`() = runTest {
        val details = detailsSource(albumDetail, fixture("plex/album-tracks.json"), mapOf("1024" to fixture("plex/track-with-chapters.json")))
            .fetchBookDetails(server, "plex_1019")

        assertEquals(
            listOf("/library/metadata/1019", "/library/metadata/1019/children", "/library/metadata/1024"),
            requests.map { it.url.encodedPath }
        )
        assertEquals(listOf(null, null, "1"), requests.map { it.url.parameters["includeChapters"] })
        assertTrue(requests.all { it.headers["X-Plex-Token"] == "tok" && it.url.parameters["X-Plex-Token"] == null })
        assertEquals(24, details.chapters.size)
        assertEquals("About the book", details.description)
        assertEquals(listOf("Fantasy"), details.genres)
        assertEquals("2024", details.publishedYear)
        assertEquals(emptyList(), details.series)
        assertEquals(47_198_808L, details.durationMs)
        assertEquals(749_847_734L, details.downloadSizeBytes)
    }

    @Test
    fun `when the first track has chapters every track is read`() = runTest {
        val multi = fixture("plex/album-tracks-multi.json")
        val withChapters = fixture("plex/track-1103-with-chapters.json").replace("\"1103\"", "\"1102\"")
        val source = detailsSource(
            albumDetail, multi,
            mapOf(
                "1102" to withChapters,
                "1103" to fixture("plex/track-1103-with-chapters.json"),
                "1104" to fixture("plex/track-1104-no-duration.json"),
            )
        )

        val content = source.fetchContent(server, "plex_1101")

        val paths = requests.map { it.url.encodedPath }
        assertEquals(listOf("/library/metadata/1101/children", "/library/metadata/1102"), paths.take(2))
        assertEquals(setOf("/library/metadata/1103", "/library/metadata/1104"), paths.drop(2).toSet()) // read side by side
        assertEquals(4, paths.size)
        assertEquals(listOf("1102", "1103", "1104"), content.tracks.map { it.ratingKey })
        assertEquals(5, content.chapters.size)
    }

    @Test
    fun `when the first track has no chapters the others are not read`() = runTest {
        val source = detailsSource(
            albumDetail, fixture("plex/album-tracks-multi.json"),
            mapOf("1102" to fixture("plex/track-1102-without-chapters.json"))
        )

        val content = source.fetchContent(server, "plex_1101")

        assertEquals(listOf("/library/metadata/1101/children", "/library/metadata/1102"), requests.map { it.url.encodedPath })
        assertEquals(listOf("Fixture title 13", "Fixture title 14", "Fixture title 15"), content.chapters.map { it.title })
    }

    @Test
    fun `an album without tracks has no chapters and asks for no track`() = runTest {
        val details = detailsSource(albumDetail, """{"MediaContainer":{"size":0}}""", emptyMap()).fetchBookDetails(server, "plex_1019")

        assertEquals(emptyList(), details.chapters)
        assertNull(details.durationMs)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a track whose detail fails is one chapter and the details still load`() = runTest {
        val source = PlexLibrarySource(
            PlexApiService(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/children")) {
                            respond(fixture("plex/album-tracks.json"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        } else respond("", HttpStatusCode.NotFound)
                    }
                ) { installApiDefaults(isDebuggable = false) }, TestPlexClientId
            )
        )

        val content = source.fetchContent(server, "plex_1019")

        assertEquals(listOf(Chapter(0, "Fixture title 12", 0L, 47_198_808L)), content.chapters)
    }

    /** An album of [count] tracks; every track's detail has one chapter, except the ones in [failing] (500). */
    private fun manyTracks(count: Int, failing: Set<String> = emptySet(), onDetail: suspend () -> Unit = {}) = PlexLibrarySource(
        PlexApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val path = request.url.encodedPath
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    if (path.endsWith("/children")) {
                        val items = (1..count).joinToString(",") { """{"ratingKey":"t$it","type":"track","title":"Track $it","duration":1000}""" }
                        respond("""{"MediaContainer":{"size":$count,"Metadata":[$items]}}""", HttpStatusCode.OK, json)
                    } else {
                        val key = path.removePrefix("/library/metadata/")
                        onDetail()
                        if (key in failing) respond("", HttpStatusCode.InternalServerError)
                        else respond(
                            """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"$key","type":"track","title":"Track","duration":1000,
                                "Chapter":[{"id":1,"tag":"Chapter of $key","startTimeOffset":0,"endTimeOffset":1000}]}]}}""",
                            HttpStatusCode.OK, json
                        )
                    }
                }
            ) { installApiDefaults(isDebuggable = false) }, TestPlexClientId
        )
    )

    @Test
    fun `one failing track among many keeps the others' chapters`() = runTest {
        val content = manyTracks(5, failing = setOf("t3")).fetchContent(server, "plex_1")

        assertEquals(
            listOf("Chapter of t1", "Chapter of t2", "Track 3", "Chapter of t4", "Chapter of t5"),
            content.chapters.map { it.title }
        )
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L), content.chapters.map { it.startMs })
    }

    @Test
    fun `a first track that cannot be read still has the others read`() = runTest {
        val content = manyTracks(3, failing = setOf("t1")).fetchContent(server, "plex_1")

        assertEquals(listOf("Track 1", "Chapter of t2", "Chapter of t3"), content.chapters.map { it.title })
    }

    @Test
    fun `track details are read at most four at a time`() = runTest {
        val running = AtomicInteger(0)
        val most = AtomicInteger(0)
        val source = manyTracks(12, onDetail = {
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(30)
            running.decrementAndGet()
        })

        val content = withContext(Dispatchers.Default) { source.fetchContent(server, "plex_1") }

        assertEquals(12, content.chapters.size)
        assertTrue(most.get() in 2..4, "at most 4 at once, and more than one: ${most.get()}")
    }
}
