package com.chapterplayer.data.remote.plex

import com.chapterplayer.testing.TestPlexClientId
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
        val sections = PlexApiService(client(fixture("plex/sections.json")), TestPlexClientId)
            .getLibrarySections("https://plex.test:32400", token = "tok")

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://plex.test:32400/library/sections", request.url.toString())
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals(3, sections.mediaContainer.size)
    }

    @Test
    fun `lists one page of a section's tracks with their albums`() = runTest {
        val items = PlexApiService(client(fixture("plex/section-tracks.json")), TestPlexClientId)
            .getSectionTracks("https://plex.test:32400/", token = "tok", sectionId = "4", start = 0, size = 200)

        val request = requests.single()
        assertEquals("/library/sections/4/all", request.url.encodedPath)
        assertEquals("10", request.url.parameters["type"])
        assertEquals("0", request.url.parameters["X-Plex-Container-Start"])
        assertEquals("200", request.headers["X-Plex-Container-Size"])
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals(4, items.mediaContainer.totalSize)
        assertEquals(listOf("2001", "2001", "2002", "2003"), items.mediaContainer.metadata?.map { it.parentRatingKey })
    }

    @Test
    fun `lists one page of a section's albums`() = runTest {
        val items = PlexApiService(client(fixture("plex/library-albums.json")), TestPlexClientId)
            .getSectionAlbums("https://plex.test:32400/", token = "tok", sectionId = "4", start = 200, size = 200)

        val request = requests.single()
        assertEquals("/library/sections/4/all", request.url.encodedPath)
        assertEquals("9", request.url.parameters["type"])
        assertEquals("200", request.url.parameters["X-Plex-Container-Start"])
        assertEquals("200", request.url.parameters["X-Plex-Container-Size"])
        assertEquals("200", request.headers["X-Plex-Container-Start"])
        assertEquals("200", request.headers["X-Plex-Container-Size"])
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals(942, items.mediaContainer.totalSize)
        assertEquals(4, items.mediaContainer.metadata?.size)
    }

    @Test
    fun `lists an album's tracks`() = runTest {
        val tracks = PlexApiService(client(fixture("plex/album-tracks.json")), TestPlexClientId)
            .getChildren("https://plex.test:32400", token = "tok", ratingKey = "1019")

        val request = requests.single()
        assertEquals("https://plex.test:32400/library/metadata/1019/children", request.url.toString())
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals("1024", tracks.mediaContainer.metadata?.single()?.ratingKey)
    }

    @Test
    fun `reads one track with its chapters`() = runTest {
        val track = PlexApiService(client(fixture("plex/track-with-chapters.json")), TestPlexClientId)
            .getMetadata("https://plex.test:32400", token = "tok", ratingKey = "1024", includeChapters = true)

        val request = requests.single()
        assertEquals("/library/metadata/1024", request.url.encodedPath)
        assertEquals("1", request.url.parameters["includeChapters"])
        assertEquals("tok", request.headers["X-Plex-Token"])
        assertEquals(24, track.mediaContainer.metadata?.single()?.chapters?.size)
    }

    @Test
    fun `reads one item without chapters unless asked`() = runTest {
        PlexApiService(client(fixture("plex/library-albums.json")), TestPlexClientId).getMetadata("https://plex.test:32400", token = "tok", ratingKey = "1013")

        assertEquals(null, requests.single().url.parameters["includeChapters"])
    }
}
