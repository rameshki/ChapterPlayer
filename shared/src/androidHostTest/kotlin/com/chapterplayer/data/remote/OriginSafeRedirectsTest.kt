package com.chapterplayer.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cover client follows redirects but never carries a server's token to another host. */
class OriginSafeRedirectsTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { request -> requests += request; answer(request) }) { followRedirects = false }
            .followRedirectsKeepingCredentialsAtHome()

    private fun MockRequestHandleScope.redirect(to: String, status: HttpStatusCode = HttpStatusCode.Found) =
        respond("", status, headersOf(HttpHeaders.Location, to))

    private fun MockRequestHandleScope.image() = respond("img", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))

    private suspend fun HttpClient.cover(url: String) = get(url) {
        header("X-Plex-Token", "plex-tok")
        header(HttpHeaders.Authorization, "Bearer abs-tok")
    }

    @Test
    fun `a redirect to another host is followed without the token`() = runTest {
        val client = client { request ->
            if (request.url.host == "plex.test") redirect("https://cdn.other.test/img.jpg") else image()
        }

        val response = client.cover("https://plex.test:32400/library/metadata/1/thumb/2")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("plex.test", "cdn.other.test"), requests.map { it.url.host })
        assertEquals("plex-tok", requests[0].headers["X-Plex-Token"])
        assertNull(requests[1].headers["X-Plex-Token"])
        assertNull(requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `another port on the same host is another origin`() = runTest {
        val client = client { request -> if (request.url.port == 32400) redirect("https://plex.test:8443/x") else image() }

        client.cover("https://plex.test:32400/a")

        assertNull(requests[1].headers["X-Plex-Token"])
    }

    @Test
    fun `a same-host redirect keeps the token, relative or absolute`() = runTest {
        val client = client { request ->
            when (request.url.encodedPath) {
                "/a" -> redirect("/b")
                "/b" -> redirect("https://plex.test:32400/c", HttpStatusCode.TemporaryRedirect)
                else -> image()
            }
        }

        val response = client.cover("https://plex.test:32400/a")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("/a", "/b", "/c"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.headers["X-Plex-Token"] == "plex-tok" && it.headers[HttpHeaders.Authorization] == "Bearer abs-tok" })
    }

    @Test
    fun `once dropped the token does not come back on a later hop home`() = runTest {
        val client = client { request ->
            when (request.url.host) {
                "plex.test" -> if (request.url.encodedPath == "/a") redirect("https://other.test/b") else image()
                else -> redirect("https://plex.test/c")
            }
        }

        client.cover("https://plex.test/a")

        assertEquals(3, requests.size)
        assertFalse(requests.drop(1).any { it.headers["X-Plex-Token"] != null })
    }

    @Test
    fun `an https cover is never sent on to plain http`() = runTest {
        val client = client { redirect("http://plex.test/a") }

        val response = client.cover("https://plex.test/a")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a redirect loop stops after the limit`() = runTest {
        val client = client { redirect("/again") }

        val response = client.cover("https://plex.test/start")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(MAX_REDIRECTS + 1, requests.size)
    }

    @Test
    fun `a plain answer is passed through untouched`() = runTest {
        val response = client { image() }.cover("https://abs.test/api/items/1/cover")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Bearer abs-tok", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `origins compare scheme, host and port`() {
        assertTrue(sameOrigin(Url("https://Plex.test/a"), Url("https://plex.test:443/b")))
        assertFalse(sameOrigin(Url("https://plex.test/a"), Url("http://plex.test/a")))
        assertFalse(sameOrigin(Url("https://plex.test/a"), Url("https://plex.test:32400/a")))
    }
}
