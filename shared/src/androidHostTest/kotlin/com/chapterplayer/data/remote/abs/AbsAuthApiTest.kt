package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.installApiDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsAuthApiTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val logs = mutableListOf<String>()

    private fun service(body: String, status: HttpStatusCode = HttpStatusCode.OK) = AbsApiService(
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { installApiDefaults(isDebuggable = true, sink = { logs += it }) }
    )

    private fun bodyText(content: OutgoingContent): String = when (content) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> error("unexpected body type ${content::class}")
    }

    @Test
    fun `reads the server status`() = runTest {
        val status = service(fixture("abs/status.json")).getStatus("http://abs.test:13378/")

        assertEquals("http://abs.test:13378/status", requests.single().url.toString())
        assertEquals(HttpMethod.Get, requests.single().method)
        assertNull(requests.single().headers[HttpHeaders.Authorization])
        assertEquals("2.37.1", status.serverVersion)
        assertEquals(listOf("local"), status.authMethods)
    }

    @Test
    fun `a status with no sign-in methods listed parses`() = runTest {
        val status = service("""{"app":"audiobookshelf"}""").getStatus("http://abs.test")
        assertEquals(emptyList(), status.authMethods)
    }

    @Test
    fun `login posts the credentials and asks for the tokens`() = runTest {
        val result = service(fixture("abs/login-response.json")).login("http://abs.test", "fixture-user", "pw-123")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://abs.test/login", request.url.toString())
        assertEquals("true", request.headers["x-return-tokens"])
        val body = bodyText(request.body)
        assertTrue(""""username":"fixture-user"""" in body && """"password":"pw-123"""" in body, body)
        assertEquals("access.fixture.token", result.user.accessToken)
        assertEquals("refresh.fixture.token", result.user.refreshToken)
        assertEquals("legacy.fixture.token", result.user.token)
        assertEquals("lib_fixture_books", result.userDefaultLibraryId)
    }

    @Test
    fun `a server that only returns the legacy token parses`() = runTest {
        val old = """{"user":{"id":"u1","username":"fixture-user","type":"user","token":"legacy.only.token"}}"""
        val result = service(old).login("http://abs.test", "fixture-user", "pw")

        assertNull(result.user.accessToken)
        assertNull(result.user.refreshToken)
        assertEquals("legacy.only.token", result.user.token)
    }

    @Test
    fun `a wrong password fails with the http status`() = runTest {
        val error = assertFailsWith<HttpStatusException> {
            service("Unauthorized", HttpStatusCode.Unauthorized).login("http://abs.test", "u", "wrong")
        }
        assertEquals(HttpStatusCode.Unauthorized, error.status)
    }

    @Test
    fun `refresh sends the refresh token in the header and returns new tokens`() = runTest {
        val result = service(fixture("abs/refresh-response.json")).refresh("http://abs.test", "refresh.fixture.token")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://abs.test/auth/refresh", request.url.toString())
        assertEquals("refresh.fixture.token", request.headers["x-refresh-token"])
        assertEquals("access.fixture.token2", result.user.accessToken)
        assertEquals("refresh.fixture.token2", result.user.refreshToken)
    }

    @Test
    fun `logging never shows the password, a token, a refresh token or a cookie`() = runTest {
        service(fixture("abs/login-response.json")).login("http://abs.test", "fixture-user", "pw-very-secret")
        service(fixture("abs/refresh-response.json")).refresh("http://abs.test", "refresh.fixture.token")

        val text = logs.joinToString("\n")
        assertTrue("http://abs.test/login" in text, "requests are still logged in debug builds")
        for (secret in listOf("pw-very-secret", "access.fixture.token", "refresh.fixture.token", "legacy.fixture.token")) {
            assertTrue(secret !in text, "the log must not contain $secret")
        }
    }
}
