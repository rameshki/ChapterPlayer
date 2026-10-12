package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlexTvApiServiceTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun service(body: String) = PlexTvApiService(
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { installApiDefaults(isDebuggable = false) }
    )

    private fun HttpRequestData.assertPlexHeaders() {
        assertEquals("ChapterPlayer", headers["X-Plex-Product"])
        assertEquals("0.1.0", headers["X-Plex-Version"])
        assertEquals("client-1", headers["X-Plex-Client-Identifier"])
        assertEquals("application/json", headers[HttpHeaders.Accept])
    }

    @Test
    fun `creates a strong sign-in code`() = runTest {
        val pin = service(fixture("plex/plextv-pin-created.json"))
            .createPin(product = "ChapterPlayer", version = "0.1.0", clientId = "client-1")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://plex.tv/api/v2/pins?strong=true", request.url.toString())
        request.assertPlexHeaders()
        assertEquals("fixturecode000000000000", pin.code)
        assertNull(pin.authToken)
    }

    @Test
    fun `checks a sign-in code by id`() = runTest {
        val pin = service(fixture("plex/plextv-pin-approved.json"))
            .checkPin(pinId = 1000001L, product = "ChapterPlayer", version = "0.1.0", clientId = "client-1")

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://plex.tv/api/v2/pins/1000001", request.url.toString())
        request.assertPlexHeaders()
        assertEquals("fixture-account-token", pin.authToken)
    }

    @Test
    fun `lists the servers on the account with https and relay connections`() = runTest {
        val servers = service(fixture("plex/plextv-resources.json"))
            .getResources(token = "acct", product = "ChapterPlayer", version = "0.1.0", clientId = "client-1")

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/v2/resources", request.url.encodedPath)
        assertEquals("1", request.url.parameters["includeHttps"])
        assertEquals("1", request.url.parameters["includeRelay"])
        assertEquals("acct", request.headers["X-Plex-Token"])
        request.assertPlexHeaders()
        assertEquals(2, servers.size)
    }
}
