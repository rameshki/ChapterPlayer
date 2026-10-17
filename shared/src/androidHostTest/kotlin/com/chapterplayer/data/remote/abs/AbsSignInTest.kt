package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.auth.AbsSignInError
import com.chapterplayer.data.auth.AbsSignInException
import com.chapterplayer.data.remote.installApiDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsSignInTest {

    private val paths = mutableListOf<String>()
    private val auth = mutableListOf<String?>()

    private fun reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) = Pair(body, status)

    private fun signIn(
        status: Pair<String, HttpStatusCode> = reply(fixture("abs/status.json")),
        login: Pair<String, HttpStatusCode> = reply(fixture("abs/login-response.json")),
        libraries: Pair<String, HttpStatusCode> = reply(fixture("abs/libraries.json")),
        throwOn: String? = null,
    ): AbsSignIn {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            auth += request.headers[HttpHeaders.Authorization]
            if (path == throwOn) throw ConnectException("refused")
            val (body, code) = when (path) {
                "/status" -> status
                "/login" -> login
                "/api/libraries" -> libraries
                else -> reply("{}", HttpStatusCode.NotFound)
            }
            respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AbsSignIn(AbsApiService(HttpClient(engine) { installApiDefaults(isDebuggable = false, sink = {}) }))
    }

    private suspend fun errorOf(block: suspend () -> Unit): AbsSignInError =
        assertFailsWith<AbsSignInException> { block() }.error

    @Test
    fun `password sign-in succeeds and keeps only book libraries`() = runTest {
        val result = signIn().withPassword("abs.test:13378", "fixture-user", "pw")

        assertEquals(listOf("/status", "/login", "/api/libraries"), paths)
        assertEquals("Bearer access.fixture.token", auth.last())
        assertEquals("http://abs.test:13378", result.baseUrl)
        assertEquals("access.fixture.token", result.token)
        assertEquals("refresh.fixture.token", result.refreshToken)
        assertEquals(listOf("lib_fixture_books", "lib_fixture_more"), result.libraries.map { it.id })
        assertTrue(result.libraries.all { it.mediaType == "book" })
    }

    @Test
    fun `the result and exception never print a token or the password`() = runTest {
        val result = signIn().withPassword("abs.test", "u", "pw-very-secret")
        assertFalse("access.fixture.token" in result.toString() || "refresh.fixture.token" in result.toString())
        val e = assertFailsWith<AbsSignInException> {
            signIn(login = reply("pw-very-secret", HttpStatusCode.Unauthorized))
                .withPassword("abs.test", "u", "pw-very-secret")
        }
        assertFalse("pw-very-secret" in e.toString())
    }

    @Test
    fun `a legacy-only server uses the token`() = runTest {
        val old = """{"user":{"id":"u1","username":"x","token":"legacy.only.token"}}"""
        val result = signIn(login = reply(old)).withPassword("abs.test", "x", "pw")
        assertEquals("legacy.only.token", result.token)
        assertNull(result.refreshToken)
        assertEquals("Bearer legacy.only.token", auth.last())
    }

    @Test
    fun `a login with no token at all is a server error`() = runTest {
        val none = """{"user":{"id":"u1","username":"x"}}"""
        assertEquals(
            AbsSignInError.ServerError(0),
            errorOf { signIn(login = reply(none)).withPassword("abs.test", "x", "pw") }
        )
    }

    @Test
    fun `a blank access token with no usable legacy token is a server error`() = runTest {
        val blank = """{"user":{"id":"u1","username":"x","accessToken":"  ","token":""}}"""
        assertEquals(
            AbsSignInError.ServerError(0),
            errorOf { signIn(login = reply(blank)).withPassword("abs.test", "x", "pw") }
        )
    }

    @Test
    fun `a blank access token falls back to a usable legacy token`() = runTest {
        val body = """{"user":{"id":"u1","username":"x","accessToken":"","token":"legacy.only.token"}}"""
        assertEquals("legacy.only.token", signIn(login = reply(body)).withPassword("abs.test", "x", "pw").token)
    }

    @Test
    fun `OpenID-only status gives LocalLoginOff`() = runTest {
        val openId = reply("""{"app":"audiobookshelf","authMethods":["openid"]}""")
        assertEquals(AbsSignInError.LocalLoginOff, errorOf { signIn(status = openId).withPassword("abs.test", "u", "pw") })
        assertEquals(listOf("/status"), paths)
    }

    @Test
    fun `empty authMethods does not give LocalLoginOff`() = runTest {
        val result = signIn(status = reply("""{"app":"audiobookshelf"}""")).withPassword("abs.test", "u", "pw")
        assertEquals("access.fixture.token", result.token)
    }

    @Test
    fun `login status codes map to errors`() = runTest {
        assertEquals(
            AbsSignInError.WrongCredentials,
            errorOf { signIn(login = reply("no", HttpStatusCode.Unauthorized)).withPassword("abs.test", "u", "pw") }
        )
        assertEquals(
            AbsSignInError.TooManyAttempts,
            errorOf { signIn(login = reply("no", HttpStatusCode.TooManyRequests)).withPassword("abs.test", "u", "pw") }
        )
        assertEquals(
            AbsSignInError.ServerError(500),
            errorOf { signIn(login = reply("no", HttpStatusCode.InternalServerError)).withPassword("abs.test", "u", "pw") }
        )
    }

    @Test
    fun `a non-JSON page at status is not Audiobookshelf`() = runTest {
        assertEquals(
            AbsSignInError.NotAudiobookshelf,
            errorOf { signIn(status = reply("<html>hello</html>")).withPassword("abs.test", "u", "pw") }
        )
    }

    @Test
    fun `an HTTP error at status is not Audiobookshelf, unless 429 or 5xx`() = runTest {
        assertEquals(
            AbsSignInError.NotAudiobookshelf,
            errorOf { signIn(status = reply("x", HttpStatusCode.NotFound)).withPassword("abs.test", "u", "pw") }
        )
        assertEquals(
            AbsSignInError.TooManyAttempts,
            errorOf { signIn(status = reply("x", HttpStatusCode.TooManyRequests)).withPassword("abs.test", "u", "pw") }
        )
        assertEquals(
            AbsSignInError.ServerError(503),
            errorOf { signIn(status = reply("x", HttpStatusCode.ServiceUnavailable)).withPassword("abs.test", "u", "pw") }
        )
    }

    @Test
    fun `an unusable address is not Audiobookshelf`() = runTest {
        assertEquals(AbsSignInError.NotAudiobookshelf, errorOf { signIn().withPassword("not a host", "u", "pw") })
    }

    @Test
    fun `a connection failure is Unreachable`() = runTest {
        assertEquals(
            AbsSignInError.Unreachable,
            errorOf { signIn(throwOn = "/status").withPassword("abs.test", "u", "pw") }
        )
    }

    @Test
    fun `only podcast libraries gives NoBookLibraries`() = runTest {
        val pods = reply("""{"libraries":[{"id":"p","name":"Pods","mediaType":"podcast"}]}""")
        assertEquals(AbsSignInError.NoBookLibraries, errorOf { signIn(libraries = pods).withPassword("abs.test", "u", "pw") })
    }

    @Test
    fun `a refused fresh token on the library call is a server error`() = runTest {
        val refused = reply("no", HttpStatusCode.Unauthorized)
        assertEquals(AbsSignInError.ServerError(401), errorOf { signIn(libraries = refused).withPassword("abs.test", "u", "pw") })
    }

    @Test
    fun `API key sign-in skips login`() = runTest {
        val result = signIn().withApiKey("http://abs.test/", "key.fixture")

        assertEquals(listOf("/status", "/api/libraries"), paths)
        assertEquals("Bearer key.fixture", auth.last())
        assertEquals("key.fixture", result.token)
        assertNull(result.refreshToken)
        assertEquals(2, result.libraries.size)
    }

    @Test
    fun `API key 401 gives Rejected`() = runTest {
        val refused = reply("no", HttpStatusCode.Unauthorized)
        assertEquals(AbsSignInError.Rejected, errorOf { signIn(libraries = refused).withApiKey("abs.test", "bad") })
    }
}
