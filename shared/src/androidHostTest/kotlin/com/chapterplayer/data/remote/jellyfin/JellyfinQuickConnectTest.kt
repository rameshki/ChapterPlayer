package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.auth.JellyfinSignInError
import com.chapterplayer.data.auth.JellyfinSignInException
import com.chapterplayer.data.auth.QuickConnectRequest
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.installProbeDefaults
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinQuickConnectResultDto
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.QuickConnectPoll
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Quick Connect steps of [JellyfinSignIn] over a mock server (spec 5.1). Every value is invented. */
class JellyfinQuickConnectTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val token = "a6f98b2a08d92285b19af86a9503bf50"
    private val serverId = "36b7674f7149d16771400fae9b9eb1f9"
    private val secret = "feedfacefeedfacefeedfacefeedface"
    private val code = "482916"
    private val installId = object : InstallId { override val value = "client-1" }

    private fun ok(body: String) = Triple(body, HttpStatusCode.OK, "application/json")
    private fun status(code: HttpStatusCode) = Triple("", code, "application/json")
    private fun initiated(authenticated: Boolean = false) =
        ok("""{"Secret":"$secret","Code":"$code","Authenticated":$authenticated,"DeviceId":"d","AppName":"a"}""")

    /** [route] answers a request with (body, status, content type), or throws; null is a 404. */
    private fun signIn(route: (HttpRequestData) -> Triple<String, HttpStatusCode, String>?): JellyfinSignIn {
        val handler: io.ktor.client.engine.mock.MockRequestHandler = { request ->
            requests += request
            val answer = route(request)
            if (answer == null) respond("", HttpStatusCode.NotFound)
            else respond(answer.first, answer.second, headersOf(HttpHeaders.ContentType, answer.third))
        }
        val info: (String) -> JellyfinClient = { id -> JellyfinClient("Android", id, "0.5.4") }
        val api = JellyfinApiService(HttpClient(MockEngine(handler)) { installApiDefaults(isDebuggable = false) }, info)
        val probe = JellyfinApiService(HttpClient(MockEngine(handler)) { installProbeDefaults(isDebuggable = false) }, info)
        return JellyfinSignIn(api, probe, installId)
    }

    private fun server(
        enabled: String = "true",
        initiate: Triple<String, HttpStatusCode, String>? = initiated(),
        connect: Triple<String, HttpStatusCode, String>? = initiated(),
        authenticate: Triple<String, HttpStatusCode, String>? = ok(fixture("jellyfin/authenticate-by-name.json")),
        views: String = fixture("jellyfin/user-views.json"),
        publicInfo: String = fixture("jellyfin/system-info-public.json"),
    ) = signIn { r ->
        val path = r.url.encodedPath
        when {
            path.endsWith("/System/Info/Public") -> ok(publicInfo)
            path.endsWith("/QuickConnect/Enabled") -> ok(enabled)
            path.endsWith("/QuickConnect/Initiate") -> initiate
            path.endsWith("/QuickConnect/Connect") -> connect
            path.endsWith("/Users/AuthenticateWithQuickConnect") -> authenticate
            path.endsWith("/UserViews") -> ok(views)
            else -> null
        }
    }

    private suspend fun error(block: suspend () -> Unit): JellyfinSignInError =
        assertFailsWith<JellyfinSignInException> { block() }.error

    private val request get() = QuickConnectRequest(secret, code)
    private fun paths() = requests.map { it.url.encodedPath.substringAfterLast("/jellyfin").ifEmpty { it.url.encodedPath } }
    private fun called(suffix: String) = requests.any { it.url.encodedPath.endsWith(suffix) }

    // ---- start

    @Test
    fun `starting checks the server, asks whether Quick Connect is on, and starts a request`() = runTest {
        val started = server().startQuickConnect("https://jf.test/jellyfin/", "row-1")
        assertEquals(secret, started.secret)
        assertEquals(code, started.code)
        assertEquals(
            listOf("/jellyfin/System/Info/Public", "/jellyfin/QuickConnect/Enabled", "/jellyfin/QuickConnect/Initiate"),
            requests.map { it.url.encodedPath },
        )
        assertEquals(HttpMethod.Post, requests.last().method)
    }

    @Test
    fun `the start sends the row's device id and no token at all`() = runTest {
        server().startQuickConnect("https://jf.test", "row-1")
        val expected = jellyfinDeviceId("client-1", "row-1")
        val authorized = requests.filter { it.headers[HttpHeaders.Authorization] != null }
        assertEquals(2, authorized.size) // Enabled and Initiate; the public info read sends nothing
        assertTrue(authorized.all { it.headers[HttpHeaders.Authorization]!!.contains("DeviceId=\"$expected\"") })
        assertTrue(requests.none { it.headers[HttpHeaders.Authorization].orEmpty().contains("Token=") })
    }

    @Test
    fun `a server with Quick Connect off says so and starts nothing`() = runTest {
        assertEquals(JellyfinSignInError.QuickConnectOff, error { server(enabled = "false").startQuickConnect("https://jf.test", "r") })
        assertFalse(called("/QuickConnect/Initiate"))
    }

    @Test
    fun `a server that refuses the Enabled question reads as off`() = runTest {
        val refused = signIn { r ->
            if (r.url.encodedPath.endsWith("/System/Info/Public")) ok(fixture("jellyfin/system-info-public.json")) else status(HttpStatusCode.Unauthorized)
        }
        assertEquals(JellyfinSignInError.QuickConnectOff, error { refused.startQuickConnect("https://jf.test", "r") })
    }

    @Test
    fun `a server error on the Enabled question is a server error, not off`() = runTest {
        val broken = signIn { r ->
            if (r.url.encodedPath.endsWith("/System/Info/Public")) ok(fixture("jellyfin/system-info-public.json")) else status(HttpStatusCode.InternalServerError)
        }
        assertEquals(JellyfinSignInError.ServerError(500), error { broken.startQuickConnect("https://jf.test", "r") })
    }

    @Test
    fun `an Initiate that is refused since the check reads as off`() = runTest {
        assertEquals(
            JellyfinSignInError.QuickConnectOff,
            error { server(initiate = status(HttpStatusCode.Unauthorized)).startQuickConnect("https://jf.test", "r") },
        )
    }

    @Test
    fun `a network failure on the Enabled question is a network failure, not off`() = runTest {
        val down = signIn { r ->
            if (r.url.encodedPath.endsWith("/System/Info/Public")) ok(fixture("jellyfin/system-info-public.json")) else throw IOException("offline")
        }
        assertEquals(JellyfinSignInError.Unreachable, error { down.startQuickConnect("https://jf.test", "r") })
    }

    @Test
    fun `an answer with no secret or no code is a server error`() = runTest {
        assertEquals(
            JellyfinSignInError.ServerError(0),
            error { server(initiate = ok("""{"Secret":"","Code":"1","Authenticated":false}""")).startQuickConnect("https://jf.test", "r") },
        )
        assertEquals(
            JellyfinSignInError.ServerError(0),
            error { server(initiate = ok("""{"Secret":"s","Authenticated":false}""")).startQuickConnect("https://jf.test", "r") },
        )
    }

    @Test
    fun `another server at the stored address is refused before Quick Connect is asked about`() = runTest {
        val e = error { server().startQuickConnect("https://jf.test", "r", expectedServerId = "ffffffffffffffffffffffffffffffff") }
        assertEquals(JellyfinSignInError.OtherServer, e)
        assertFalse(called("/QuickConnect/Enabled"))
        assertEquals(listOf("/System/Info/Public"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `an address that is not Jellyfin or too old stops at the check`() = runTest {
        assertEquals(
            JellyfinSignInError.NotJellyfin,
            error { server(publicInfo = """{"Version":"1.2.3"}""").startQuickConnect("https://jf.test", "r") },
        )
        assertEquals(JellyfinSignInError.InvalidAddress, error { server().startQuickConnect("", "r") })
        assertFalse(called("/QuickConnect/Enabled"))
    }

    // ---- poll

    @Test
    fun `a read says approved or still waiting`() = runTest {
        assertEquals(QuickConnectPoll.APPROVED, server(connect = initiated(authenticated = true)).pollQuickConnect("https://jf.test", "r", request))
        assertEquals(QuickConnectPoll.WAITING, server(connect = initiated(authenticated = false)).pollQuickConnect("https://jf.test", "r", request))
    }

    @Test
    fun `a request the server does not know ends the wait`() = runTest {
        assertEquals(QuickConnectPoll.UNKNOWN, server(connect = status(HttpStatusCode.NotFound)).pollQuickConnect("https://jf.test", "r", request))
        assertEquals(QuickConnectPoll.UNKNOWN, server(connect = status(HttpStatusCode.Unauthorized)).pollQuickConnect("https://jf.test", "r", request))
    }

    @Test
    fun `a failed read is tried again, whatever it was`() = runTest {
        assertEquals(QuickConnectPoll.UNREACHABLE, server(connect = status(HttpStatusCode.InternalServerError)).pollQuickConnect("https://jf.test", "r", request))
        assertEquals(QuickConnectPoll.UNREACHABLE, server(connect = ok("<html>not json</html>")).pollQuickConnect("https://jf.test", "r", request))
        val down = signIn { throw IOException("offline") }
        assertEquals(QuickConnectPoll.UNREACHABLE, down.pollQuickConnect("https://jf.test", "r", request))
    }

    @Test
    fun `the secret goes in the query of the read and nowhere else`() = runTest {
        server().pollQuickConnect("https://jf.test", "row-1", request)
        val read = requests.single()
        assertEquals(HttpMethod.Get, read.method)
        assertEquals(secret, read.url.parameters["secret"])
        assertFalse(read.headers.entries().any { (_, values) -> values.any { secret in it } })
        assertFalse(read.headers[HttpHeaders.Authorization].orEmpty().contains("Token="))
        assertTrue(read.headers[HttpHeaders.Authorization].orEmpty().contains("DeviceId=\"${jellyfinDeviceId("client-1", "row-1")}\""))
    }

    // ---- finish

    @Test
    fun `an approved request signs in like a password does`() = runTest {
        val result = server().finishQuickConnect("https://jf.test/jellyfin/", "row-1", request)
        assertEquals(token, result.token)
        assertEquals(serverId, result.serverId)
        assertEquals("12.4.0", result.version)
        assertEquals(1, result.libraries.size)
        assertEquals("row-1", result.libraries.single().serverId)
        assertEquals(
            listOf("/jellyfin/System/Info/Public", "/jellyfin/Users/AuthenticateWithQuickConnect", "/jellyfin/UserViews"),
            requests.map { it.url.encodedPath },
        )
        assertFalse(result.toString().contains(token), result.toString())
    }

    @Test
    fun `the final sign in posts the secret in the body, with the header and no token`() = runTest {
        server().finishQuickConnect("https://jf.test", "row-1", request)
        val post = requests.single { it.url.encodedPath.endsWith("/Users/AuthenticateWithQuickConnect") }
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("""{"Secret":"$secret"}""", (post.body as TextContent).text.replace(" ", ""))
        assertFalse(post.headers[HttpHeaders.Authorization].orEmpty().contains("Token="))
        assertFalse(post.url.toString().contains(secret))
    }

    @Test
    fun `a refused sign in is not approved`() = runTest {
        for (code in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.NotFound)) {
            assertEquals(
                JellyfinSignInError.QuickConnectNotApproved,
                error { server(authenticate = status(code)).finishQuickConnect("https://jf.test", "r", request) },
            )
        }
        assertEquals(
            JellyfinSignInError.ServerError(500),
            error { server(authenticate = status(HttpStatusCode.InternalServerError)).finishQuickConnect("https://jf.test", "r", request) },
        )
    }

    @Test
    fun `a user with no books library is told so, as with a password`() = runTest {
        val e = error { server(views = """{"Items":[{"Id":"v","Name":"Movies","CollectionType":"movies"}],"TotalRecordCount":1}""").finishQuickConnect("https://jf.test", "r", request) }
        assertEquals(JellyfinSignInError.NoBooksLibrary, e)
    }

    @Test
    fun `finishing with another server's identity is refused before the secret is sent`() = runTest {
        val e = error { server().finishQuickConnect("https://jf.test", "r", request, expectedServerId = "ffffffffffffffffffffffffffffffff") }
        assertEquals(JellyfinSignInError.OtherServer, e)
        assertFalse(called("/Users/AuthenticateWithQuickConnect"))
    }

    // ---- secrecy

    @Test
    fun `the secret and the code never show in a toString or an exception`() {
        val dto = JellyfinQuickConnectResultDto(secret, code, false)
        assertFalse(dto.toString().contains(secret) || dto.toString().contains(code), dto.toString())
        assertFalse(request.toString().contains(secret) || request.toString().contains(code), request.toString())
        val e = JellyfinSignInException(JellyfinSignInError.QuickConnectNotApproved)
        assertFalse((e.message ?: "").contains(secret))
        assertNull(e.cause)
    }

    @Test
    fun `the Enabled answer is a bare boolean`() = runTest {
        val info: (String) -> JellyfinClient = { id -> JellyfinClient("Android", id, "0.5.4") }
        fun api(body: String) = JellyfinApiService(
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) { installApiDefaults(isDebuggable = false) }, info,
        )
        assertTrue(api("true").quickConnectEnabled("https://jf.test", "d"))
        assertFalse(api("false").quickConnectEnabled("https://jf.test", "d"))
    }
}
