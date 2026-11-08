package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.auth.JellyfinSignInError
import com.chapterplayer.data.auth.JellyfinSignInException
import com.chapterplayer.data.auth.JellyfinSignInResult
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.installProbeDefaults
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinSignInTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val token = "a6f98b2a08d92285b19af86a9503bf50"
    private val serverId = "36b7674f7149d16771400fae9b9eb1f9"
    private val installId = object : InstallId { override val value = "client-1" }

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        Triple(body, status, "application/json")

    /** [route] answers a path with (body, status, content type); null is a 404. */
    private fun signIn(route: (String) -> Triple<String, HttpStatusCode, String>?): JellyfinSignIn {
        val handler: MockRequestHandler = { request ->
            requests += request
            val answer = route(request.url.encodedPath)
            if (answer == null) respond("", HttpStatusCode.NotFound)
            else respond(answer.first, answer.second, headersOf(HttpHeaders.ContentType, answer.third))
        }
        val info: (String) -> JellyfinClient = { id -> JellyfinClient("Android", id, "0.4.1") }
        val api = JellyfinApiService(HttpClient(MockEngine(handler)) { installApiDefaults(isDebuggable = false) }, info)
        val probe = JellyfinApiService(HttpClient(MockEngine(handler)) { installProbeDefaults(isDebuggable = false) }, info)
        return JellyfinSignIn(api, probe, installId)
    }

    /** A server that answers every call from the fixtures, with [publicInfo] and [views] swappable. */
    private fun server(
        publicInfo: String = "system-info-public.json",
        views: String = fixture("jellyfin/user-views.json"),
        authStatus: HttpStatusCode = HttpStatusCode.OK,
    ) = signIn { path ->
        when {
            path.endsWith("/System/Info/Public") -> json(fixture("jellyfin/$publicInfo"))
            path.endsWith("/Users/AuthenticateByName") ->
                if (authStatus == HttpStatusCode.OK) json(fixture("jellyfin/authenticate-by-name.json")) else json("", authStatus)
            path.endsWith("/UserViews") -> json(views)
            else -> null
        }
    }

    private suspend fun error(block: suspend () -> Unit): JellyfinSignInError =
        assertFailsWith<JellyfinSignInException> { block() }.error

    private fun sentPassword() = requests.any { it.url.encodedPath.endsWith("/Users/AuthenticateByName") }

    @Test
    fun `success returns the token server id and one library`() = runTest {
        val result = server().withPassword("https://jf.test/jellyfin/", "row-1", "u", "p")

        assertEquals(token, result.token)
        assertEquals(serverId, result.serverId)
        assertEquals("52d8fc64326c7e0848c4f143abb47ba1", result.userId)
        assertEquals("12.4.0", result.version)
        assertEquals(listOf(LibraryInfo("row-1", "40fd54c7fab6b3a985f36b461a437081", "Audiobooks")), result.libraries)
        assertEquals(
            listOf("/jellyfin/System/Info/Public", "/jellyfin/Users/AuthenticateByName", "/jellyfin/UserViews"),
            requests.map { it.url.encodedPath },
        )
        assertFalse(result.toString().contains(token), result.toString())
    }

    @Test
    fun `the device id in the header is the row's`() = runTest {
        server().withPassword("https://jf.test", "row-1", "u", "p")
        val expected = jellyfinDeviceId("client-1", "row-1")
        val authorized = requests.filter { it.headers[HttpHeaders.Authorization] != null }
        assertEquals(2, authorized.size)
        assertTrue(authorized.all { it.headers[HttpHeaders.Authorization]!!.contains("DeviceId=\"$expected\"") })
        // The public info read sends no header at all; the views read sends the new token.
        assertEquals(null, requests.first().headers[HttpHeaders.Authorization])
        assertTrue(requests.last().headers[HttpHeaders.Authorization]!!.contains("Token=\"$token\""))
    }

    @Test
    fun `html answer is not jellyfin`() = runTest {
        val e = error {
            signIn { path ->
                if (path.endsWith("/System/Info/Public")) Triple("<html><body>Sign in</body></html>", HttpStatusCode.OK, "text/html")
                else json(fixture("jellyfin/authenticate-by-name.json"))
            }.withPassword("https://jf.test", "row-1", "u", "p")
        }
        assertEquals(JellyfinSignInError.NotJellyfin, e)
        assertFalse(sentPassword())
    }

    @Test
    fun `a json without ProductName is not jellyfin`() = runTest {
        val e = error {
            signIn { json("""{"Version":"1.2.3","Id":"x"}""") }.withPassword("https://jf.test", "row-1", "u", "p")
        }
        assertEquals(JellyfinSignInError.NotJellyfin, e)
        assertFalse(sentPassword())
    }

    @Test
    fun `a redirect is said to be one and sends no password`() = runTest {
        val e = error {
            signIn { Triple("", HttpStatusCode.Found, "text/html") }.withPassword("https://jf.test", "row-1", "u", "p")
        }
        assertEquals(JellyfinSignInError.Redirected, e)
        assertFalse(sentPassword())
        assertEquals(1, requests.size)
    }

    @Test
    fun `wrong password is wrong credentials`() = runTest {
        assertEquals(JellyfinSignInError.WrongCredentials, error {
            server(authStatus = HttpStatusCode.Unauthorized).withPassword("https://jf.test", "row-1", "u", "bad")
        })
    }

    @Test
    fun `another failed sign-in is a server error with its code`() = runTest {
        assertEquals(JellyfinSignInError.ServerError(500), error {
            server(authStatus = HttpStatusCode.InternalServerError).withPassword("https://jf.test", "row-1", "u", "p")
        })
    }

    @Test
    fun `a too old server is refused before the password is sent`() = runTest {
        assertEquals(JellyfinSignInError.TooOld("10.9.11"), error {
            server(publicInfo = "system-info-public-10-9.json").withPassword("https://jf.test", "row-1", "u", "p")
        })
        assertFalse(sentPassword())
    }

    @Test
    fun `a 10-10 server signs in`() = runTest {
        val result = server(publicInfo = "system-info-public-10-10.json").withPassword("https://jf.test", "row-1", "u", "p")
        assertEquals("10.10.7", result.version)
    }

    @Test
    fun `unreachable is unreachable`() = runTest {
        val api = JellyfinApiService(HttpClient(MockEngine { throw IOException("connect failed") }) { installApiDefaults(false) }) {
            JellyfinClient("Android", it, "0.4.1")
        }
        assertEquals(JellyfinSignInError.Unreachable, error {
            JellyfinSignIn(api, api, installId).withPassword("https://jf.test", "row-1", "u", "p")
        })
    }

    @Test
    fun `no books view is no books library`() = runTest {
        val noBooks = """{"Items":[{"Id":"10aae6df2f1368214a0e5f52fd20b11a","Name":"Music","CollectionType":"music"}],"TotalRecordCount":1}"""
        assertEquals(JellyfinSignInError.NoBooksLibrary, error {
            server(views = noBooks).withPassword("https://jf.test", "row-1", "u", "p")
        })
    }

    @Test
    fun `sign in again on another server id is refused`() = runTest {
        val expected = "a".repeat(32)
        assertEquals(JellyfinSignInError.OtherServer, error {
            server().withPassword("https://jf.test", "row-1", "u", "p", expectedServerId = expected)
        })
        assertFalse(sentPassword())
    }

    @Test
    fun `sign in again on the same server goes ahead`() = runTest {
        val result = server().withPassword("https://jf.test", "row-1", "u", "p", expectedServerId = serverId.uppercase())
        assertEquals(token, result.token)
    }

    @Test
    fun `an empty or invalid address asks for a valid one and nothing is asked`() = runTest {
        assertEquals(JellyfinSignInError.InvalidAddress, error { server().withPassword("ftp://jf.test", "row-1", "u", "p") })
        assertEquals(JellyfinSignInError.InvalidAddress, error { server().withPassword("  ", "row-1", "u", "p") })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a certificate problem is a secure connection error`() = runTest {
        val api = JellyfinApiService(HttpClient(MockEngine { throw javax.net.ssl.SSLHandshakeException("bad cert") }) { installApiDefaults(false) }) {
            JellyfinClient("Android", it, "0.4.1")
        }
        assertEquals(JellyfinSignInError.SecureConnection, error {
            JellyfinSignIn(api, api, installId).withPassword("https://jf.test", "row-1", "u", "p")
        })
    }

    @Test
    fun `the result hides the token`() {
        val r = JellyfinSignInResult(token = token, serverId = serverId, userId = "u1", version = "12.4.0", libraries = emptyList())
        assertFalse(r.toString().contains(token))
        assertEquals("Sign-in failed", JellyfinSignInException(JellyfinSignInError.WrongCredentials).message)
    }
}
