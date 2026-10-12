package com.chapterplayer.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpClientTest {

    private fun client(
        status: HttpStatusCode,
        isDebuggable: Boolean = false,
        logs: MutableList<String> = mutableListOf()
    ) = HttpClient(
        MockEngine { respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) }
    ) {
        installApiDefaults(isDebuggable = isDebuggable, sink = { logs += it })
    }

    @Test
    fun `an error status fails with the same message people see today`() = runTest {
        val cases = listOf(
            HttpStatusCode.Unauthorized to "HTTP 401 Unauthorized",
            HttpStatusCode.NotFound to "HTTP 404 Not Found",
            HttpStatusCode.InternalServerError to "HTTP 500 Internal Server Error",
        )
        for ((status, message) in cases) {
            val error = assertFailsWith<HttpStatusException> { client(status).get("http://server.test/x") }
            assertEquals(message, error.message)
            assertEquals(status, error.status)
        }
    }

    @Test
    fun `a success status returns the response`() = runTest {
        assertEquals(HttpStatusCode.OK, client(HttpStatusCode.OK).get("http://server.test/x").status)
    }

    @Test
    fun `debug logging never shows the authorization or plex token`() = runTest {
        val logs = mutableListOf<String>()

        client(HttpStatusCode.OK, isDebuggable = true, logs = logs).get("http://server.test/x") {
            header(HttpHeaders.Authorization, "Bearer secret-abs-token")
            header("X-Plex-Token", "secret-plex-token")
        }

        val text = logs.joinToString("\n")
        assertTrue(text.contains("http://server.test/x"), "debug builds should log requests")
        assertFalse(text.contains("secret-abs-token"), "ABS token leaked into the log")
        assertFalse(text.contains("secret-plex-token"), "Plex token leaked into the log")
    }

    @Test
    fun `release builds log nothing`() = runTest {
        val logs = mutableListOf<String>()

        client(HttpStatusCode.OK, isDebuggable = false, logs = logs).get("http://server.test/x")

        assertTrue(logs.isEmpty())
    }
}
