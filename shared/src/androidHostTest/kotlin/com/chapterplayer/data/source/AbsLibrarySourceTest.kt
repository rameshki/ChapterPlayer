package com.chapterplayer.data.source

import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.model.SeriesRef
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.AbsSessionManager
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun source(
        items: String = fixture("abs/items-page-with-total.json"),
        collections: String = fixture("abs/collections-page.json"),
    ) = AbsLibrarySource(
        AbsApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val path = request.url.encodedPath
                    val body = when {
                        path == "/api/libraries" -> fixture("abs/libraries.json")
                        path.endsWith("/collections") -> collections
                        else -> items
                    }
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
    fun `a configured library is still read when another library is excluded`() = runTest {
        source().fetchBooks(server.copy(libraryId = "lib_fixture_more", excludedLibraryIds = setOf("lib_fixture_books")))

        assertEquals(listOf("/api/libraries/lib_fixture_more/items"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `a configured library that is turned off is not read`() = runTest {
        val books = source().fetchBooks(server.copy(libraryId = "lib_fixture_more", excludedLibraryIds = setOf("lib_fixture_more")))

        assertEquals(emptyList(), books)
        assertTrue(requests.none { it.url.encodedPath.endsWith("/items") })
    }

    @Test
    fun `a snapshot of a configured library that is turned off has no books or collections`() = runTest {
        val snapshot = source().fetchSnapshot(server.copy(libraryId = "lib_fixture_more", excludedLibraryIds = setOf("lib_fixture_more")))

        assertEquals(emptyList(), snapshot.books)
        assertEquals(emptyList(), snapshot.collections)
        assertTrue(requests.none { it.url.encodedPath.endsWith("/items") || it.url.encodedPath.endsWith("/collections") })
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
    fun `maps the library, the added date and the series of a book`() = runTest {
        val books = source().fetchBooks(server.copy(libraryId = "lib_fixture_books"))

        val first = books.first()
        assertEquals("lib_fixture_books", first.libraryId)
        assertEquals(1_700_000_001_000L, first.addedAt) // milliseconds, as the server gives them
        assertEquals("Fixture Series", first.seriesName)
        assertEquals("1", first.seriesSequence)
    }

    @Test
    fun `the library of a book is the library that was read`() = runTest {
        val books = source().fetchBooks(server)

        assertEquals(setOf("lib_fixture_books", "lib_fixture_more"), books.map { it.libraryId }.toSet())
    }

    @Test
    fun `a blank series name gives no series`() = runTest {
        val book = source().fetchBooks(server.copy(libraryId = "lib_fixture_books"))[2]

        assertNull(book.seriesName)
        assertNull(book.seriesSequence)
    }

    @Test
    fun `a series name without a hash has no sequence`() = runTest {
        val page = """{"results":[{"id":"x","addedAt":5,"media":{"metadata":{"title":"T","seriesName":"Plain Series"}}}],"limit":100,"page":0}"""

        val book = source(page).fetchBooks(server.copy(libraryId = "lib")).single()

        assertEquals("Plain Series", book.seriesName)
        assertNull(book.seriesSequence)
    }

    @Test
    fun `a book without a series or a date gets the defaults`() = runTest {
        val page = """{"results":[{"id":"x","media":{"metadata":{"title":"T"}}}],"limit":100,"page":0}"""

        val book = source(page).fetchBooks(server.copy(libraryId = "lib")).single()

        assertNull(book.seriesName)
        assertEquals(0L, book.addedAt)
        assertEquals("lib", book.libraryId)
    }

    @Test
    fun `lists every book library including turned-off ones and skips podcasts`() = runTest {
        val libraries = source().fetchLibraries(server.copy(excludedLibraryIds = setOf("lib_fixture_more")))

        assertEquals(
            listOf(
                LibraryInfo("srv1", "lib_fixture_books", "Fixture Books"),
                LibraryInfo("srv1", "lib_fixture_more", "Fixture More Books"),
            ),
            libraries
        )
    }

    @Test
    fun `a configured library is the only one listed, with its name`() = runTest {
        val libraries = source().fetchLibraries(server.copy(libraryId = "lib_fixture_more"))

        assertEquals(listOf(LibraryInfo("srv1", "lib_fixture_more", "Fixture More Books")), libraries)
    }

    @Test
    fun `a configured library the server does not list uses its id as the name`() = runTest {
        val libraries = source().fetchLibraries(server.copy(libraryId = "lib_unknown"))

        assertEquals(listOf(LibraryInfo("srv1", "lib_unknown", "lib_unknown")), libraries)
    }

    @Test
    fun `one sync asks the server for its libraries once`() = runTest {
        val snapshot = source().fetchSnapshot(server.copy(excludedLibraryIds = setOf("lib_fixture_more")))

        assertEquals(1, requests.count { it.url.encodedPath == "/api/libraries" })
        assertTrue(snapshot.books.isNotEmpty())
        assertEquals(
            listOf(
                LibraryInfo("srv1", "lib_fixture_books", "Fixture Books"),
                LibraryInfo("srv1", "lib_fixture_more", "Fixture More Books"),
            ),
            snapshot.libraries
        )
        assertEquals(listOf("col_fixture01", "col_fixture02", "col_fixture03"), snapshot.collections?.map { it.id })
        assertTrue(snapshot.books.all { it.libraryId == "lib_fixture_books" })
    }

    @Test
    fun `a sync of a configured library asks for the library list once and reads only that library`() = runTest {
        val snapshot = source().fetchSnapshot(server.copy(libraryId = "lib_fixture_more"))

        assertEquals(1, requests.count { it.url.encodedPath == "/api/libraries" })
        assertEquals(listOf(LibraryInfo("srv1", "lib_fixture_more", "Fixture More Books")), snapshot.libraries)
        assertTrue(snapshot.books.all { it.libraryId == "lib_fixture_more" })
    }

    @Test
    fun `reads the collections of every library that is turned on, in server order`() = runTest {
        val collections = source().fetchCollections(server.copy(excludedLibraryIds = setOf("lib_fixture_more")))

        assertEquals(
            listOf("/api/libraries", "/api/libraries/lib_fixture_books/collections"),
            requests.map { it.url.encodedPath }
        )
        assertEquals(listOf("col_fixture01", "col_fixture02", "col_fixture03"), collections.map { it.id })
        assertEquals(listOf("abs_li_fixture01", "abs_li_fixture02"), collections.first().bookIds)
        assertEquals("srv1", collections.first().serverId)
        assertEquals("Fixture Favourites", collections.first().name)
        assertEquals(emptyList(), collections[2].bookIds)
    }

    @Test
    fun `an empty collections page gives no collections`() = runTest {
        val collections = source(collections = """{"results":[],"total":0,"limit":100,"page":0}""")
            .fetchCollections(server.copy(libraryId = "lib_fixture_books"))

        assertEquals(emptyList(), collections)
        assertEquals(listOf("/api/libraries/lib_fixture_books/collections"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `reads collections page by page until a short page`() = runTest {
        val full = (0 until 100).joinToString(",", "[", "]") { """{"id":"c$it","name":"N$it"}""" }
        val seenPages = mutableListOf<String?>()
        val paged = AbsLibrarySource(
            AbsApiService(
                HttpClient(
                    MockEngine { request ->
                        val page = request.url.parameters["page"]
                        seenPages += page
                        val body = if (page == "0") """{"results":$full,"total":101,"limit":100,"page":0}"""
                        else """{"results":[{"id":"last","name":"Last"}],"total":101,"limit":100,"page":1}"""
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                ) { installApiDefaults(isDebuggable = false) }
            ),
            PassThroughAuthorizer
        )

        val collections = paged.fetchCollections(server.copy(libraryId = "lib"))

        assertEquals(101, collections.size)
        assertEquals(listOf<String?>("0", "1"), seenPages)
    }

    @Test
    fun `stops reading collections when a page adds nothing new`() = runTest {
        val full = (0 until 100).joinToString(",", "[", "]") { "{\"id\":\"c$it\",\"name\":\"N$it\"}" }
        var calls = 0
        val ignoresPage = AbsLibrarySource(
            AbsApiService(
                HttpClient(
                    MockEngine {
                        calls++
                        respond(
                            "{\"results\":$full,\"total\":1000,\"limit\":100,\"page\":0}",
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                ) { installApiDefaults(isDebuggable = false) }
            ),
            PassThroughAuthorizer
        )

        val collections = ignoresPage.fetchCollections(server.copy(libraryId = "lib"))

        assertEquals(100, collections.size)
        assertEquals(2, calls)
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

    // --- book details ---

    private val detailRequests = mutableListOf<HttpRequestData>()

    private fun detailSource(
        authorizer: AbsAuthorizer = PassThroughAuthorizer,
        status: (HttpRequestData) -> HttpStatusCode = { HttpStatusCode.OK },
    ) = AbsLibrarySource(
        AbsApiService(
            HttpClient(
                MockEngine { request ->
                    detailRequests += request
                    val code = status(request)
                    respond(
                        if (code == HttpStatusCode.OK) fixture("abs/item-detail.json") else "no",
                        code, headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ) { installApiDefaults(isDebuggable = false) }
        ),
        authorizer
    )

    @Test
    fun `book details ask for the raw item id expanded, with the bearer`() = runTest {
        detailSource().fetchBookDetails(server, "abs_li_fixture01")

        val request = detailRequests.single()
        assertEquals("/api/items/li_fixture01", request.url.encodedPath)
        assertEquals("1", request.url.parameters["expanded"])
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `only the leading abs prefix is removed from the id`() {
        assertEquals("li_1", "abs_li_1".toServerItemId())
        assertEquals("abs_x", "abs_abs_x".toServerItemId())
        assertEquals("li_1", "li_1".toServerItemId())
    }

    @Test
    fun `book details map the fixture`() = runTest {
        val details = detailSource().fetchBookDetails(server, "abs_li_fixture01")

        assertEquals(3, details.chapters.size)
        assertEquals("Opening", details.chapters.first().title)
        assertEquals(1_800_250L, details.chapters.first().endMs)
        assertEquals(listOf(SeriesRef("Fixture Saga", "2")), details.series)
        assertEquals("An invented story.", details.description)
    }

    @Test
    fun `book details fail on a server error`() = runTest {
        assertFailsWith<HttpStatusException> {
            detailSource(status = { HttpStatusCode.InternalServerError }).fetchBookDetails(server, "abs_li_fixture01")
        }
    }

    @Test
    fun `book details refresh once on a 401 and then succeed`() = runTest {
        val enc = { s: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(s.encodeToByteArray()) }
        fun jwt(exp: Long) = enc("""{"alg":"HS256"}""") + "." + enc("""{"userId":"u","exp":$exp}""") + ".sig"
        val now = 1_000_000L
        val oldToken = jwt(now + 3600)
        val newToken = jwt(now + 7200)
        var refreshes = 0
        val stored = MutableStateFlow(server.copy(token = oldToken, refreshToken = "refresh-old"))
        val servers = object : ServerRepository {
            override fun observeAll(): Flow<List<ServerConfig>> = stored.map { listOf(it) }
            override suspend fun getById(id: String): ServerConfig? = stored.value
            override suspend fun save(config: ServerConfig) { stored.value = config }
            override suspend fun delete(id: String) {}
            override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
        }
        val api = AbsApiService(
            HttpClient(
                MockEngine { request ->
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    if (request.url.encodedPath == "/auth/refresh") {
                        refreshes++
                        respond(
                            """{"user":{"id":"u","username":"x","accessToken":"$newToken","refreshToken":"refresh-new"}}""",
                            HttpStatusCode.OK, json
                        )
                    } else {
                        detailRequests += request
                        if (request.headers[HttpHeaders.Authorization] == "Bearer $newToken")
                            respond(fixture("abs/item-detail.json"), HttpStatusCode.OK, json)
                        else respond("no", HttpStatusCode.Unauthorized)
                    }
                }
            ) { installApiDefaults(isDebuggable = false) }
        )

        val details = AbsLibrarySource(api, AbsSessionManager(api, servers) { now }).fetchBookDetails(stored.value, "abs_li_fixture01")

        assertEquals(3, details.chapters.size)
        assertEquals(1, refreshes)
        assertEquals(listOf("Bearer $oldToken", "Bearer $newToken"), detailRequests.map { it.headers[HttpHeaders.Authorization] })
    }
}
