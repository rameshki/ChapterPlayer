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
import kotlin.test.assertTrue

/** Starting and closing a playback session through the Audiobookshelf source. */
class AbsLibraryPlaybackTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.ABS, name = "Test", baseUrl = "http://abs.test/", token = "tok", libraryId = null
    )

    private object PassThroughAuthorizer : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            call("Bearer " + server.token)
    }

    private fun api(status: HttpStatusCode = HttpStatusCode.OK) = AbsApiService(
        HttpClient(MockEngine { request ->
            requests += request
            if (status == HttpStatusCode.OK && request.url.encodedPath.endsWith("/play"))
                respond(fixture("abs/play-session.json"), status, json)
            else respond("{}", status, json)
        }) { installApiDefaults(isDebuggable = false) }
    )

    private fun bodyOf(request: HttpRequestData) =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test
    fun `start posts the play request with the bearer and the right body`() = runTest {
        val session = AbsLibrarySource(api(), PassThroughAuthorizer).startPlayback(server, "abs_li_fixture01")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/items/li_fixture01/play", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val body = bodyOf(request)
        assertTrue(body["forceDirectPlay"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["forceTranscode"]!!.jsonPrimitive.boolean)
        assertEquals("ChapterPlayer", body["deviceInfo"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
        assertEquals("exoplayer", body["mediaPlayer"]!!.jsonPrimitive.content)
        assertEquals("srv1", session.serverId)
        assertEquals("abs_li_fixture01", session.bookId)
        assertEquals("http://abs.test/api/items/li_fixture01/file/ino_fixture_1", session.tracks.first().url)
    }

    @Test
    fun `a server error throws`() = runTest {
        assertFailsWith<HttpStatusException> {
            AbsLibrarySource(api(HttpStatusCode.InternalServerError), PassThroughAuthorizer).startPlayback(server, "abs_x")
        }
    }

    @Test
    fun `close posts the session path with the numbers in seconds`() = runTest {
        AbsLibrarySource(api(), PassThroughAuthorizer).closePlayback(server, "play_fixture01", 1_735_250, 90_500)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/session/play_fixture01/close", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        val body = bodyOf(request)
        assertEquals(1735.25, body["currentTime"]!!.jsonPrimitive.content.toDouble())
        assertEquals(90.5, body["timeListened"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `start refreshes once on a 401 and then succeeds`() = runTest {
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
        val playRequests = mutableListOf<HttpRequestData>()
        val api = AbsApiService(
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/auth/refresh") {
                    refreshes++
                    respond(
                        """{"user":{"id":"u","username":"x","accessToken":"$newToken","refreshToken":"refresh-new"}}""",
                        HttpStatusCode.OK, json
                    )
                } else {
                    playRequests += request
                    if (request.headers[HttpHeaders.Authorization] == "Bearer $newToken")
                        respond(fixture("abs/play-session.json"), HttpStatusCode.OK, json)
                    else respond("no", HttpStatusCode.Unauthorized)
                }
            }) { installApiDefaults(isDebuggable = false) }
        )

        val session = AbsLibrarySource(api, AbsSessionManager(api, servers) { now }).startPlayback(stored.value, "abs_li_fixture01")

        assertEquals(2, session.tracks.size)
        assertEquals(1, refreshes)
        assertEquals(listOf("Bearer $oldToken", "Bearer $newToken"), playRequests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun `close refreshes once on a 401 and then succeeds`() = runTest {
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
        val closeRequests = mutableListOf<HttpRequestData>()
        val api = AbsApiService(
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/auth/refresh") {
                    refreshes++
                    respond(
                        """{"user":{"id":"u","username":"x","accessToken":"$newToken","refreshToken":"refresh-new"}}""",
                        HttpStatusCode.OK, json
                    )
                } else {
                    closeRequests += request
                    if (request.headers[HttpHeaders.Authorization] == "Bearer $newToken") respond("{}", HttpStatusCode.OK, json)
                    else respond("no", HttpStatusCode.Unauthorized)
                }
            }) { installApiDefaults(isDebuggable = false) }
        )

        AbsLibrarySource(api, AbsSessionManager(api, servers) { now }).closePlayback(stored.value, "play_fixture01", 1_000, 500)

        assertEquals(1, refreshes)
        assertEquals(listOf("Bearer $oldToken", "Bearer $newToken"), closeRequests.map { it.headers[HttpHeaders.Authorization] })
    }
}
