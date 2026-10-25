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
import kotlin.test.assertTrue

class PlexConnectionProberTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun identity(id: String) =
        """{"MediaContainer":{"size":0,"claimed":true,"machineIdentifier":"$id","version":"1.43.1"}}"""

    private fun prober(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = KtorPlexConnectionProbe(
        HttpClient(MockEngine { request -> requests += request; answer(request) }) { installProbeDefaults(isDebuggable = false) }
    )

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `asks the identity of the server and sends no token`() = runTest {
        val problem = prober { respond(identity("fixturemachine01"), HttpStatusCode.OK, json) }
            .probe("https://192-0-2-11.abc.plex.direct:32400/", "fixturemachine01")

        assertNull(problem)
        val request = requests.single()
        assertEquals("https://192-0-2-11.abc.plex.direct:32400/identity", request.url.toString())
        assertNull(request.headers["X-Plex-Token"])
        assertTrue(request.headers.names().none { it.contains("token", ignoreCase = true) })
        assertTrue(request.url.parameters.names().none { it.contains("token", ignoreCase = true) })
    }

    @Test
    fun `another server answering loses`() = runTest {
        val problem = prober { respond(identity("someoneelse"), HttpStatusCode.OK, json) }.probe("http://192.0.2.11:32400", "fixturemachine01")

        assertEquals(ConnectionProblem(ProblemKind.WRONG_SERVER), problem)
    }

    @Test
    fun `a success that is not a Plex identity (a captive portal) loses`() = runTest {
        val problem = prober { respond("<html>Log in to the hotel Wi-Fi</html>", HttpStatusCode.OK) }.probe("http://192.0.2.11:32400", "fixturemachine01")

        assertEquals(ConnectionProblem(ProblemKind.WRONG_SERVER), problem)
        val unknownServer = prober { respond("<html></html>", HttpStatusCode.OK) }.probe("http://192.0.2.11:32400", null)
        assertEquals(ConnectionProblem(ProblemKind.WRONG_SERVER), unknownServer)
    }

    @Test
    fun `an error status is an HTTP problem`() = runTest {
        val problem = prober { respond("", HttpStatusCode.ServiceUnavailable) }.probe("http://192.0.2.11:32400", "m")

        assertEquals(ConnectionProblem(ProblemKind.HTTP, 503), problem)
    }

    @Test
    fun `a redirect is not followed and counts as a failure`() = runTest {
        val problem = prober { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/")) }
            .probe("http://192.0.2.11:32400", "m")

        assertEquals(ConnectionProblem(ProblemKind.HTTP, 302), problem)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a name the network will not resolve is classified`() = runTest {
        val problem = prober { throw UnknownHostException("Unable to resolve host") }.probe("https://x.plex.direct:32400", "m")

        assertEquals(ProblemKind.UNKNOWN_HOST, problem?.kind)
    }
}
