package com.chapterplayer.data.source

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.domain.plex.PLEX_AUDIO_MISSING_TEXT
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Playing a Plex book: the session the app builds from the tracks, the server's progress and the reports back. */
class PlexLibraryPlaybackTest {

    private val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())

    private val server = ServerConfig(
        id = "srv1", source = ServerSource.PLEX, name = "Test", baseUrl = "http://plex.test:32400", token = "tok", libraryId = null
    )

    private val noChapters = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1102","type":"track","title":"Fixture title 13"}]}}"""

    /** Answers the track list with [tracks], a track's detail without chapters, and every report with an empty 200. */
    private fun source(
        tracks: String = fixture("plex/album-tracks-multi.json"),
        failReports: HttpStatusCode? = null,
    ) = PlexLibrarySource(
        PlexApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val path = request.url.encodedPath
                    when {
                        path.startsWith("/:/") ->
                            if (failReports != null) respond("", failReports) else respond("", HttpStatusCode.OK)
                        path.endsWith("/children") ->
                            respond(tracks, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respond(noChapters, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            ) { installApiDefaults(isDebuggable = false) },
            object : PlexClientIdentifier {
                override val value = "client-1"
            },
        )
    )

    private fun reports() = requests.filter { it.url.encodedPath.startsWith("/:/") }

    // -- Starting ----------------------------------------------------------------------------

    @Test
    fun `a session has one track per file on one timeline, with plain addresses and the chapters`() = runTest {
        val session = source().startPlayback(server, "plex_1101")

        assertEquals("srv1", session.serverId)
        assertEquals("plex_1101", session.bookId)
        assertEquals(
            listOf(
                "http://plex.test:32400/library/parts/1202/1700000000/file.mp3",
                "http://plex.test:32400/library/parts/1203/1700000000/file.mp3",
                "http://plex.test:32400/library/parts/1204/1700000000/file.mp3",
            ),
            session.tracks.map { it.url },
        )
        assertEquals(listOf(0L, 600_000L, 1_500_000L), session.tracks.map { it.startMs })
        assertEquals(listOf("audio/mpeg", "audio/mpeg", "audio/mpeg"), session.tracks.map { it.mimeType })
        assertEquals(listOf("Fixture title 13", "Fixture title 14", "Fixture title 15"), session.chapters.map { it.title })
        assertEquals(1_500_000L, session.durationMs)
        assertEquals(0L, session.startPositionMs)
        assertTrue(session.tracks.none { "tok" in it.url })
        assertTrue(requests.all { it.headers["X-Plex-Token"] == "tok" && it.url.parameters["X-Plex-Token"] == null })
    }

    @Test
    fun `every start is a new session`() = runTest {
        val source = source()
        assertNotEquals(source.startPlayback(server, "plex_1101").sessionId, source.startPlayback(server, "plex_1101").sessionId)
    }

    @Test
    fun `a track without a file fails the start with a safe message`() = runTest {
        val tracks = fixture("plex/album-tracks-multi.json").replace("\"Part\"", "\"NoPart\"")

        val e = assertFailsWith<IllegalStateException> { source(tracks).startPlayback(server, "plex_1101") }
        assertEquals(PLEX_AUDIO_MISSING_TEXT, e.message)
    }
}
