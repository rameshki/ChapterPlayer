package com.chapterplayer.data.source

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.AbsSessionManager
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The bookmark calls through the Audiobookshelf source. */
class AbsLibraryBookmarkTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.ABS, name = "Test", baseUrl = "http://abs.test/", token = "tok", libraryId = null
    )

    private object PassThroughAuthorizer : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            call("Bearer " + server.token)
    }

    private fun source(status: HttpStatusCode = HttpStatusCode.OK, body: (HttpRequestData) -> String = { "{}" }) = AbsLibrarySource(
        AbsApiService(
            HttpClient(MockEngine { request ->
                requests += request
                respond(if (status == HttpStatusCode.OK) body(request) else "{}", status, json)
            }) { installApiDefaults(isDebuggable = false) }
        ),
        PassThroughAuthorizer
    )

    private fun bodyOf(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test
    fun `fetching reads the bookmarks of the me call with the app book ids`() = runTest {
        val rows = source(body = { fixture("abs/bookmarks.json") }).fetchBookmarks(server)

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/me", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        assertEquals(
            listOf(
                Bookmark("abs_li_fixture01", 120, "Start of chapter two", 1760000100000L, BookmarkState.SYNCED),
                Bookmark("abs_li_fixture01", 3723, "Bookmark at 1:02:03", 1760000200000L, BookmarkState.SYNCED),
                Bookmark("abs_li_fixture02", 45, "Favourite line", 1760000300000L, BookmarkState.SYNCED),
            ),
            rows
        )
    }

    @Test
    fun `a bookmark without an item id is skipped and a failing me call throws`() = runTest {
        val body = """{"bookmarks":[{"libraryItemId":"","time":1,"title":"x"},{"libraryItemId":"li_ok","time":2,"title":"y"}]}"""
        assertEquals(listOf("abs_li_ok"), source(body = { body }).fetchBookmarks(server).map { it.bookId })
        assertFailsWith<HttpStatusException> { source(HttpStatusCode.InternalServerError).fetchBookmarks(server) }
    }

    @Test
    fun `creating posts the second and the title to the item without the prefix`() = runTest {
        source().createBookmark(server, "abs_li_fixture01", 3723, "Bookmark at 1:02:03")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/me/item/li_fixture01/bookmark", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val body = bodyOf(request)
        assertEquals(3723L, body["time"]!!.jsonPrimitive.content.toLong())
        assertEquals("Bookmark at 1:02:03", body["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `renaming patches the item with the second and the new title`() = runTest {
        source().renameBookmark(server, "abs_li_fixture01", 120, "New name")

        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/api/me/item/li_fixture01/bookmark", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val body = bodyOf(request)
        assertEquals(120L, body["time"]!!.jsonPrimitive.content.toLong())
        assertEquals("New name", body["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deleting sends the second in the path`() = runTest {
        source().deleteBookmark(server, "abs_li_fixture01", 3723)

        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/api/me/item/li_fixture01/bookmark/3723", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `deleting a bookmark the server no longer has counts as done`() = runTest {
        source(HttpStatusCode.NotFound).deleteBookmark(server, "abs_a", 5)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a server error fails every bookmark write`() = runTest {
        val failing = source(HttpStatusCode.InternalServerError)
        assertFailsWith<HttpStatusException> { failing.deleteBookmark(server, "abs_a", 5) }
        assertFailsWith<HttpStatusException> { failing.createBookmark(server, "abs_a", 5, "t") }
        assertFailsWith<HttpStatusException> { failing.renameBookmark(server, "abs_a", 5, "t") }
        // A 404 on create or rename is a failure: only a delete can be "already done".
        assertFailsWith<HttpStatusException> { source(HttpStatusCode.NotFound).createBookmark(server, "abs_a", 5, "t") }
    }

    // ---- 401 through the real session manager ----

    private class Refreshing(
        val source: AbsLibrarySource,
        val server: ServerConfig,
        val seen: MutableList<HttpRequestData>,
        val refreshes: () -> Int,
        val oldToken: String,
        val newToken: String,
    )

    private fun refreshing(): Refreshing {
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
        val seen = mutableListOf<HttpRequestData>()
        val api = AbsApiService(
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/auth/refresh") {
                    refreshes++
                    respond(
                        """{"user":{"id":"u","username":"x","accessToken":"$newToken","refreshToken":"refresh-new"}}""",
                        HttpStatusCode.OK, json
                    )
                } else {
                    seen += request
                    if (request.headers[HttpHeaders.Authorization] == "Bearer $newToken") respond("{}", HttpStatusCode.OK, json)
                    else respond("no", HttpStatusCode.Unauthorized)
                }
            }) { installApiDefaults(isDebuggable = false) }
        )
        return Refreshing(AbsLibrarySource(api, AbsSessionManager(api, servers) { now }), stored.value, seen, { refreshes }, oldToken, newToken)
    }

    @Test
    fun `create refreshes once on a 401 and then succeeds`() = runTest {
        val r = refreshing()

        r.source.createBookmark(r.server, "abs_li_fixture01", 10, "t")

        assertEquals(1, r.refreshes())
        assertEquals(listOf("Bearer ${r.oldToken}", "Bearer ${r.newToken}"), r.seen.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun `delete refreshes once on a 401 and then succeeds`() = runTest {
        val r = refreshing()

        r.source.deleteBookmark(r.server, "abs_li_fixture01", 10)

        assertEquals(1, r.refreshes())
        assertTrue(r.seen.all { it.method == HttpMethod.Delete })
        assertEquals(listOf("Bearer ${r.oldToken}", "Bearer ${r.newToken}"), r.seen.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun `rename and fetch also refresh once on a 401`() = runTest {
        val r = refreshing()
        r.source.renameBookmark(r.server, "abs_li_fixture01", 10, "t")
        assertEquals(1, r.refreshes())

        val r2 = refreshing()
        r2.source.fetchBookmarks(r2.server)
        assertEquals(1, r2.refreshes())
    }
}
