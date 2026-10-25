package com.chapterplayer.data.source

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.domain.stats.epochDayOf
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
import kotlin.test.assertFails

/** The listening history call through the Audiobookshelf source. */
class AbsLibraryListeningStatsTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val server = ServerConfig(id = "srv1", source = ServerSource.ABS, name = "Test", baseUrl = "http://abs.test/", token = "tok")

    private object PassThroughAuthorizer : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            call("Bearer " + server.token)
    }

    private fun source(status: HttpStatusCode = HttpStatusCode.OK) = AbsLibrarySource(
        AbsApiService(
            HttpClient(MockEngine { request ->
                requests += request
                respond(if (status == HttpStatusCode.OK) fixture("abs/listening-stats.json") else "{}", status, json)
            }) { installApiDefaults(isDebuggable = false) }
        ),
        PassThroughAuthorizer
    )

    @Test
    fun `the history is read from the me listening stats endpoint with the bearer token`() = runTest {
        val stats = source().fetchListeningStats(server)

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("http://abs.test/api/me/listening-stats", request.url.toString())
        assertEquals("Bearer tok", request.headers[HttpHeaders.Authorization])
        assertEquals(3600L, stats.days[epochDayOf(2026, 10, 20)])
        assertEquals(7201L, stats.items["abs_li_fixture01"])
    }

    @Test
    fun `a refused request fails`() = runTest {
        assertFails { source(HttpStatusCode.InternalServerError).fetchListeningStats(server) }
    }
}
