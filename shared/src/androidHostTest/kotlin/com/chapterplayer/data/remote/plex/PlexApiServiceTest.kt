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

class PlexApiServiceTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(body: String) = HttpClient(
        MockEngine { request ->
            requests += request
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    ) { installApiDefaults(isDebuggable = false) }

    @Test
    fun `lists library sections asking for json`() = runTest {
        val sections = PlexApiService(client(fixture("plex/sections.json")))
            .getLibrarySections("https://plex.test:32400", token = "tok")

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://plex.test:32400/library/sections", request.url.toString())
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals(3, sections.mediaContainer.size)
    }

    @Test
    fun `lists a section asking for chapters`() = runTest {
        val items = PlexApiService(client(fixture("plex/library-all-artists.json")))
            .getLibraryAll("https://plex.test:32400/", token = "tok", sectionId = "4")

        val request = requests.single()
        assertEquals("/library/sections/4/all", request.url.encodedPath)
        assertEquals("1", request.url.parameters["includeChapters"])
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals(5, items.mediaContainer.metadata?.size)
    }
}
