package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.installProbeDefaults
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.ProblemKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlexConnectionProberTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun prober(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = KtorPlexConnectionProbe(
        HttpClient(MockEngine { request -> requests += request; answer(request) }) { installProbeDefaults(isDebuggable = false) }
    )

    @Test
    fun `asks the identity of the server with the token in a header`() = runTest {
        val problem = prober { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            .probe("https://192-0-2-11.abc.plex.direct:32400/", "server-token")

        assertNull(problem)
        val request = requests.single()
        assertEquals("https://192-0-2-11.abc.plex.direct:32400/identity", request.url.toString())
        assertEquals("server-token", request.headers["X-Plex-Token"])
        assertEquals(false, request.url.toString().contains("server-token"))
    }

    @Test
    fun `a refused token is an HTTP problem`() = runTest {
        val problem = prober { respond("", HttpStatusCode.Unauthorized) }.probe("http://192.0.2.11:32400", "t")

        assertEquals(ConnectionProblem(ProblemKind.HTTP, 401), problem)
    }

    @Test
    fun `a redirect is not followed and counts as a failure`() = runTest {
        val problem = prober { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/")) }
            .probe("http://192.0.2.11:32400", "t")

        assertEquals(ConnectionProblem(ProblemKind.HTTP, 302), problem)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a name the network will not resolve is classified`() = runTest {
        val problem = prober { throw UnknownHostException("Unable to resolve host") }.probe("https://x.plex.direct:32400", "t")

        assertEquals(ProblemKind.UNKNOWN_HOST, problem?.kind)
    }
}
