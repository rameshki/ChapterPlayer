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
import com.chapterplayer.domain.plex.PLEX_LENGTH_UNKNOWN_TEXT
import com.chapterplayer.data.auth.SignInRequiredException
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

    /** The recorded three-track book, with the length its third track lacks in the recording (600 + 900 + 300 s). */
    private val playable = fixture("plex/album-tracks-multi.json")
        .replace("\"ratingKey\": \"1104\",", "\"ratingKey\": \"1104\", \"duration\": 300000,")

    /** The servers whose token a refused call blanked. */
    private val signedOut = mutableListOf<String>()

    /** Answers the track list with [tracks], a track's detail without chapters, and every report with an empty 200. */
    private fun source(
        tracks: String = playable,
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
        ),
        markSignedOut = { signedOut += it.id },
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
        assertEquals(1_800_000L, session.durationMs)
        assertEquals(listOf(600_000L, 900_000L, 300_000L), session.tracks.map { it.durationMs })
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
        // The second track's file is missing.
        val second = playable.indexOf("\"Part\"", playable.indexOf("\"Part\"") + 1)
        val tracks = playable.replaceRange(second, second + "\"Part\"".length, "\"NoPart\"")

        val e = assertFailsWith<IllegalStateException> { source(tracks).startPlayback(server, "plex_1101") }
        assertEquals(PLEX_AUDIO_MISSING_TEXT, e.message)
    }

    @Test
    fun `a track of unknown length fails the start with a safe message`() = runTest {
        // As recorded: the third track has no length.
        val e = assertFailsWith<IllegalStateException> { source(fixture("plex/album-tracks-multi.json")).startPlayback(server, "plex_1101") }
        assertEquals(PLEX_LENGTH_UNKNOWN_TEXT, e.message)
    }

    @Test
    fun `a refused token blanks the sign-in and asks to sign in again`() = runTest {
        val refused = PlexLibrarySource(
            PlexApiService(
                HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) { installApiDefaults(isDebuggable = false) },
                object : PlexClientIdentifier {
                    override val value = "client-1"
                },
            ),
            markSignedOut = { signedOut += it.id },
        )

        assertFailsWith<SignInRequiredException> { refused.startPlayback(server, "plex_1101") }
        assertFailsWith<SignInRequiredException> { refused.fetchProgress(server, "plex_1101") }
        assertFailsWith<SignInRequiredException> { refused.pushProgress(server, "plex_1101", 1L, 1L, isFinished = false) }
        assertEquals(listOf("srv1", "srv1", "srv1"), signedOut)
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
    fun `a pause says paused, and playing on into the next track scrobbles the one before once`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        source.syncPlayback(server, session.sessionId, 590_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 600_500L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 602_000L, 1_500L, session.durationMs, isPlaying = false)
        // Back into track 1 and played on again: it was scrobbled in this session already.
        source.syncPlayback(server, session.sessionId, 595_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 605_000L, 10_000L, session.durationMs, isPlaying = true)

        assertEquals(
            listOf("timeline 1102", "timeline 1103", "scrobble 1102", "timeline 1103", "timeline 1102", "timeline 1103"),
            names(),
        )
        assertEquals(
            listOf("playing", "playing", "paused", "playing", "playing"),
            reports().filter { it.url.encodedPath == "/:/timeline" }.map { it.url.parameters["state"] },
        )
    }

    @Test
    fun `a seek forward over tracks scrobbles none of them`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        source.syncPlayback(server, session.sessionId, 100_000L, 10_000L, session.durationMs, isPlaying = true)
        // A chapter jump to the third track within the next ten seconds.
        source.syncPlayback(server, session.sessionId, 1_510_000L, 10_000L, session.durationMs, isPlaying = true)

        assertEquals(listOf("timeline 1102", "timeline 1104"), names())
    }

    @Test
    fun `within the last 5 seconds of a track it is scrobbled`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        source.syncPlayback(server, session.sessionId, 586_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 596_000L, 10_000L, session.durationMs, isPlaying = true)
        source.syncPlayback(server, session.sessionId, 601_000L, 5_000L, session.durationMs, isPlaying = true)

        assertEquals(listOf("timeline 1102", "timeline 1102", "scrobble 1102", "timeline 1103"), names())
    }

    @Test
    fun `closing sends a stopped timeline and ends the session`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        source.syncPlayback(server, session.sessionId, 690_000L, 10_000L, session.durationMs, isPlaying = false)
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
    fun `a session that never reported closes without a word to the server`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        requests.clear()

        // The start was rolled back: the controller closes the new session at 0.
        source.closePlayback(server, session.sessionId, 0L, 0L)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `marked finished while loaded, the session reports nothing more until marked not finished`() = runTest {
        val source = source()
        val session = source.startPlayback(server, "plex_1101")
        source.syncPlayback(server, session.sessionId, 700_000L, 10_000L, session.durationMs, isPlaying = true)
        requests.clear()

        source.pushProgress(server, "plex_1101", 700_000L, session.durationMs, isFinished = true)
        val afterMark = reports().size
        source.syncPlayback(server, session.sessionId, 710_000L, 10_000L, session.durationMs, isPlaying = true)
        source.closePlayback(server, "unknown", 1L, 1L)
        assertEquals(afterMark, reports().size)
        assertEquals(listOf("scrobble 1102", "scrobble 1103", "scrobble 1104"), names())

        source.pushProgress(server, "plex_1101", 0L, session.durationMs, isFinished = false)
        requests.clear()
        source.syncPlayback(server, session.sessionId, 20_000L, 10_000L, session.durationMs, isPlaying = true)
        assertEquals(listOf("timeline 1102"), names())
    }

    @Test
    fun `a refused report asks to sign in again`() = runTest {
        val source = source(failReports = HttpStatusCode.Unauthorized)
        val session = source.startPlayback(server, "plex_1101")

        assertFailsWith<SignInRequiredException> { source.syncPlayback(server, session.sessionId, 1_000L, 1L, 1L, isPlaying = true) }
        assertEquals(listOf("srv1"), signedOut)
    }

    @Test
    fun `another failed report is an error, not a sign-in`() = runTest {
        val source = source(failReports = HttpStatusCode.InternalServerError)
        val session = source.startPlayback(server, "plex_1101")

        assertFailsWith<HttpStatusException> { source.syncPlayback(server, session.sessionId, 1_000L, 1L, 1L, isPlaying = true) }
        assertTrue(signedOut.isEmpty())
    }
}
