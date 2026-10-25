package com.chapterplayer.data.source

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.domain.playback.ServerProgress
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
import kotlin.test.assertNull
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

    // -- The server's position ----------------------------------------------------------------

    @Test
    fun `the server position is the finished tracks plus the offset in the one in progress`() = runTest {
        val progress = source(fixture("plex/album-tracks-progress.json")).fetchProgress(server, "plex_1101")

        assertEquals(ServerProgress(1_620_000L, 1_800_000L, isFinished = false, updatedAtMs = 1_700_000_300_000L), progress)
        // One call: the track list carries each track's state.
        assertEquals(listOf("/library/metadata/1101/children"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `a book never played has no server progress`() = runTest {
        assertNull(source().fetchProgress(server, "plex_1101"))
    }

    @Test
    fun `a session resumes where the server says`() = runTest {
        val session = source(fixture("plex/album-tracks-progress.json")).startPlayback(server, "plex_1101")

        assertEquals(1_620_000L, session.startPositionMs)
    }

    // -- Reports while playing ----------------------------------------------------------------

    private fun names() = reports().map { it.url.encodedPath.removePrefix("/:/") + " " + it.url.parameters["key"]!!.removePrefix("/library/metadata/") }

    @Test
    fun `a sync while playing sends a timeline for the track that holds the position`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        source.syncPlayback(server, session.sessionId, 700_000L, 10_000L, session.durationMs, isPlaying = true)

        val timeline = reports().single()
        assertEquals("/:/timeline", timeline.url.encodedPath)
        assertEquals("1103", timeline.url.parameters["ratingKey"])
        assertEquals("/library/metadata/1103", timeline.url.parameters["key"])
        assertEquals("playing", timeline.url.parameters["state"])
        assertEquals("100000", timeline.url.parameters["time"])
        assertEquals("900000", timeline.url.parameters["duration"])
        assertEquals("com.plexapp.plugins.library", timeline.url.parameters["identifier"])
        assertEquals("tok", timeline.headers["X-Plex-Token"])
        assertEquals("client-1", timeline.headers["X-Plex-Client-Identifier"])
        assertEquals("ChapterPlayer", timeline.headers["X-Plex-Product"])
        assertEquals(null, timeline.url.parameters["X-Plex-Token"])
    }

    @Test
    fun `a pause says stopped, and moving on to the next track scrobbles the one before once`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        source.syncPlayback(server, session.sessionId, 590_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 610_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 620_000L, 10_000L, session.durationMs, isPlaying = false)
        // Back into track 1 and on again: it was scrobbled in this session already.
        source.syncPlayback(server, session.sessionId, 100_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 700_000L, 10_000L, session.durationMs, isPlaying = true)

        assertEquals(
            listOf("timeline 1102", "timeline 1103", "scrobble 1102", "timeline 1103", "timeline 1102", "timeline 1103"),
            names(),
        )
        assertEquals(listOf("playing", "playing", "stopped", "playing", "playing"), reports().filter { it.url.encodedPath == "/:/timeline" }.map { it.url.parameters["state"] })
    }

    @Test
    fun `within the last 5 seconds of a track it is scrobbled`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        source.syncPlayback(server, session.sessionId, 596_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 601_000L, 10_000L, session.durationMs, isPlaying = true)

        assertEquals(listOf("timeline 1102", "scrobble 1102", "timeline 1103"), names())
    }

    @Test
    fun `closing sends a stopped timeline and ends the session`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        source.closePlayback(server, session.sessionId, 700_000L, 0L)

        assertEquals(listOf("timeline 1103"), names())
        assertEquals("stopped", reports().single().url.parameters["state"])
        // A closed (or unknown) session fails, so the syncer queues the position instead.
        assertFailsWith<IllegalStateException> { source.syncPlayback(server, session.sessionId, 1L, 1L, 1L, isPlaying = true) }
        source.closePlayback(server, session.sessionId, 700_000L, 0L)
        assertEquals(1, reports().size)
    }

    @Test
    fun `a refused report fails the sync`() = runTest {
        val source = source(failReports = HttpStatusCode.Unauthorized)
        val session = source.startPlayback(server, "plex_1101")

        assertFailsWith<HttpStatusException> { source.syncPlayback(server, session.sessionId, 1_000L, 1L, 1L, isPlaying = true) }
    }
}
