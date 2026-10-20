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
import com.chapterplayer.domain.playback.ServerProgress
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session sync and the progress calls through the Audiobookshelf source. */
class AbsLibraryProgressTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.ABS, name = "Test", baseUrl = "http://abs.test/", token = "tok", libraryId = null
    )

    private object PassThroughAuthorizer : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            call("Bearer " + server.token)
    }

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: (HttpRequestData) -> String = { "OK" }) = AbsApiService(
        HttpClient(MockEngine { request ->
            requests += request
            respond(if (status == HttpStatusCode.OK) body(request) else "{}", status, json)
        }) { installApiDefaults(isDebuggable = false) }
    )

    private fun source(status: HttpStatusCode = HttpStatusCode.OK, body: (HttpRequestData) -> String = { "OK" }) =
        AbsLibrarySource(api(status, body), PassThroughAuthorizer)

    private fun bodyOf(request: HttpRequestData) =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test
    fun `sync posts the session path with the numbers in seconds`() = runTest {
        source().syncPlayback(server, "play_fixture01", 1_735_250, 10_500, 5_400_500)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/session/play_fixture01/sync", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val body = bodyOf(request)
        assertEquals(1735.25, body["currentTime"]!!.jsonPrimitive.content.toDouble())
        assertEquals(10.5, body["timeListened"]!!.jsonPrimitive.content.toDouble())
        assertEquals(5400.5, body["duration"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `a session the server no longer knows fails the sync`() = runTest {
        assertFailsWith<HttpStatusException> { source(HttpStatusCode.NotFound).syncPlayback(server, "gone", 1, 1, 1) }
        assertFailsWith<HttpStatusException> { source(HttpStatusCode.InternalServerError).syncPlayback(server, "x", 1, 1, 1) }
    }

    @Test
    fun `fetching progress asks for the item without the prefix and reads it`() = runTest {
        val progress = source(body = { fixture("abs/progress.json") }).fetchProgress(server, "abs_li_fixture01")

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/me/progress/li_fixture01", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        assertEquals(ServerProgress(1_735_250L, 5_400_500L, false, 1760000000123L), progress)
    }

    @Test
    fun `no progress on the server is null`() = runTest {
        assertNull(source(HttpStatusCode.NotFound).fetchProgress(server, "abs_x"))
    }

    @Test
    fun `a server error reading progress throws`() = runTest {
        assertFailsWith<HttpStatusException> { source(HttpStatusCode.InternalServerError).fetchProgress(server, "abs_x") }
    }

    @Test
    fun `pushing progress patches the item with the position the duration and the fraction`() = runTest {
        source().pushProgress(server, "abs_li_fixture01", 1_350_000, 5_400_000, isFinished = false)

        val request = requests.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/api/me/progress/li_fixture01", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val body = bodyOf(request)
        assertEquals(1350.0, body["currentTime"]!!.jsonPrimitive.content.toDouble())
        assertEquals(5400.0, body["duration"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.25, body["progress"]!!.jsonPrimitive.content.toDouble())
        assertEquals(false, body["isFinished"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the fraction is zero when the duration is unknown and the finished flag is passed on`() = runTest {
        source().pushProgress(server, "abs_a", 5_000, 0, isFinished = true)

        val body = bodyOf(requests.single())
        assertEquals(0.0, body["progress"]!!.jsonPrimitive.content.toDouble())
        assertTrue(body["isFinished"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a failed patch throws`() = runTest {
        assertFailsWith<HttpStatusException> { source(HttpStatusCode.InternalServerError).pushProgress(server, "abs_a", 1, 2, false) }
    }

    @Test
    fun `all progress comes from the me call keyed by the app book id`() = runTest {
        val all = source(body = { fixture("abs/me.json") }).fetchAllProgress(server)

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/me", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        assertEquals(setOf("abs_li_fixture01", "abs_li_fixture02", "abs_li_fixture03"), all.keys)
        assertEquals(ServerProgress(3_600_000L, 3_600_000L, true, 1760000500000L), all["abs_li_fixture02"])
    }

    @Test
    fun `progress without an item id or for a podcast episode is skipped`() = runTest {
        val body = """{"mediaProgress":[
            {"libraryItemId":"","currentTime":1,"duration":2},
            {"libraryItemId":"li_pod","episodeId":"ep1","currentTime":1,"duration":2},
            {"libraryItemId":"li_ok","currentTime":1,"duration":2}
        ]}"""
        val all = source(body = { body }).fetchAllProgress(server)

        assertEquals(setOf("abs_li_ok"), all.keys)
    }

    @Test
    fun `a failing me call throws`() = runTest {
        assertFailsWith<HttpStatusException> { source(HttpStatusCode.InternalServerError).fetchAllProgress(server) }
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

    private fun refreshing(respondOk: (HttpRequestData) -> String): Refreshing {
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
                    if (request.headers[HttpHeaders.Authorization] == "Bearer $newToken") respond(respondOk(request), HttpStatusCode.OK, json)
                    else respond("no", HttpStatusCode.Unauthorized)
                }
            }) { installApiDefaults(isDebuggable = false) }
        )
        return Refreshing(AbsLibrarySource(api, AbsSessionManager(api, servers) { now }), stored.value, seen, { refreshes }, oldToken, newToken)
    }

    @Test
    fun `sync refreshes once on a 401 and then succeeds`() = runTest {
        val r = refreshing { "OK" }

        r.source.syncPlayback(r.server, "play_fixture01", 1_000, 500, 9_000)

        assertEquals(1, r.refreshes())
        assertEquals(listOf("Bearer ${r.oldToken}", "Bearer ${r.newToken}"), r.seen.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun `patch refreshes once on a 401 and then succeeds`() = runTest {
        val r = refreshing { "{}" }

        r.source.pushProgress(r.server, "abs_li_fixture01", 1_000, 9_000, false)

        assertEquals(1, r.refreshes())
        assertEquals(listOf("Bearer ${r.oldToken}", "Bearer ${r.newToken}"), r.seen.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun `reading progress refreshes once on a 401 and then succeeds`() = runTest {
        val r = refreshing { fixture("abs/progress.json") }

        val progress = r.source.fetchProgress(r.server, "abs_li_fixture01")

        assertEquals(1_735_250L, progress?.positionMs)
        assertEquals(1, r.refreshes())
    }
}
