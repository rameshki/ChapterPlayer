package com.chapterplayer.data.remote

import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.domain.jellyfin.JellyfinClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The debug HTTP log with the Jellyfin `MediaBrowser` header: the token, the password and the bodies never show. */
class HttpClientFactoryLoggingTest {

    private val token = "a6f98b2a08d92285b19af86a9503bf50"
    private val password = "pw-very-secret-123"

    private fun api(logs: MutableList<String>, probe: Boolean = false): JellyfinApiService {
        val engine = MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/Users/AuthenticateByName") -> fixture("jellyfin/authenticate-by-name.json")
                request.url.encodedPath.endsWith("/System/Info/Public") -> fixture("jellyfin/system-info-public.json")
                else -> fixture("jellyfin/user-views.json")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            if (probe) installProbeDefaults(isDebuggable = true, sink = { logs += it })
            else installApiDefaults(isDebuggable = true, sink = { logs += it })
        }
        return JellyfinApiService(client) { JellyfinClient("Android", it, "0.4.1") }
    }

    @Test
    fun `the debug log never shows the jellyfin token the password or a body`() = runTest {
        val logs = mutableListOf<String>()
        val api = api(logs)
        api.authenticateByName("https://jf.test", "chapterplayer-client-1-row-1", "fixture-user", password)
        api.getUserViews("https://jf.test", "chapterplayer-client-1-row-1", token)
        api(logs, probe = true).getPublicInfo("https://jf.test")

        val all = logs.joinToString("\n")
        assertTrue(all.contains("/Users/AuthenticateByName"), "the requests are logged at all")
        assertFalse(all.contains(token), "token in the log")
        assertFalse(all.contains(password), "password in the log")
        assertFalse(all.contains("MediaBrowser"), "the auth header value in the log")
        assertFalse(all.contains("AccessToken"), "a response body in the log")
        assertFalse(all.contains("\"Pw\""), "the sign-in body in the log")
        assertFalse(all.contains("ProductName"), "the public info body in the log")
    }
}
