package com.chapterplayer.data.downloads

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.installDownloadDefaults
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.source.PlexLibrarySource
import com.chapterplayer.domain.downloads.PlannedFile
import com.chapterplayer.domain.plex.PLEX_AUDIO_MISSING_TEXT
import com.chapterplayer.domain.plex.PLEX_SIZE_UNKNOWN_TEXT
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Planning a Plex book's download from its tracks and building each file's request. Synthetic data only. */
class PlexDownloadSourceTest {

    private val server = ServerConfig(
        id = "plex1", source = ServerSource.PLEX, name = "Test Plex", baseUrl = "http://plex.test:32400", token = "tok", libraryId = null
    )

    /** The recorded three-track album, as recorded (its third track has no length). */
    private val recorded = fixture("plex/album-tracks-multi.json")

    /** The same album with the length its third track lacks in the recording (600 + 900 + 300 s). */
    private val tracks = recorded.replace("\"ratingKey\": \"1104\",", "\"ratingKey\": \"1104\", \"duration\": 300000,")
    private val noChapters = """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1102","type":"track","title":"Fixture title 13"}]}}"""

    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val signedOut = mutableListOf<String>()

    private inner class Rig(
        var children: String = tracks,
        var childrenStatus: HttpStatusCode = HttpStatusCode.OK,
        /** Requests to this host fail to connect. */
        val deadHost: String? = null,
        onConnectionFailure: suspend (ServerConfig, Throwable) -> ServerConfig? = { _, _ -> null },
        prepare: suspend (ServerConfig) -> ServerConfig? = { it },
        val parts: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NotFound) },
    ) {
        val servers = FakeServers(server)
        private val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                request.url.host == deadHost -> throw java.net.ConnectException("Connection refused")
                path.startsWith("/library/parts/") -> parts(request)
                path.endsWith("/children") ->
                    if (childrenStatus != HttpStatusCode.OK) respond("", childrenStatus)
                    else respond(children, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(noChapters, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val plex = PlexLibrarySource(
            PlexApiService(
                HttpClient(engine) { installApiDefaults(isDebuggable = false, sink = {}) },
                object : PlexClientIdentifier { override val value = "client-1" },
            ),
            markSignedOut = { signedOut += it.id },
        )
        val source = PlexDownloadSource(
            plex, servers, HttpClient(engine) { installDownloadDefaults(isDebuggable = false, sink = {}) },
            onConnectionFailure = onConnectionFailure, prepare = prepare,
        )
    }

    @Test
    fun `the plan is the album's tracks in play order with their sizes, lengths, types and the chapters`() = runBlocking {
        val plan = Rig().source.plan(server, "plex_1101")

        assertNull(plan.failure)
        assertEquals(
            listOf(
                PlannedFile("1102", "1102.mp3", 9_600_000, 0, 600_000, "audio/mpeg"),
                PlannedFile("1103", "1103.mp3", 14_400_000, 1, 900_000, "audio/mpeg"),
                PlannedFile("1104", "1104.mp3", 1_000_000, 2, 300_000, "audio/mpeg"),
            ),
            plan.files
        )
        assertEquals(3, plan.chapters.size)
    }

    @Test
    fun `a file is fetched from its part path under the address in use, with the token header only`() = runBlocking {
        val rig = Rig()
        rig.source.plan(server, "plex_1101")

        val fetch = rig.source.authorized(server, "plex_1101", "1103") { it }

        assertEquals("http://plex.test:32400/library/parts/1203/1700000000/file.mp3", fetch.url)
        assertEquals(listOf("X-Plex-Token" to "tok"), fetch.headers)
        assertTrue("tok" !in fetch.url)
    }

    @Test
    fun `a changed address is used by the next request`() = runBlocking {
        val rig = Rig()
        rig.source.plan(server, "plex_1101")
        rig.servers.save(server.copy(baseUrl = "https://10-0-0-2.fixture.plex.direct:32400"))

        // The job still holds the old configuration; the request follows the saved one.
        val fetch = rig.source.authorized(server, "plex_1101", "1102") { it }

        assertEquals("https://10-0-0-2.fixture.plex.direct:32400/library/parts/1202/1700000000/file.mp3", fetch.url)
        assertEquals("https://10-0-0-2.fixture.plex.direct:32400", fetch.server.baseUrl)
    }

    @Test
    fun `after a restart the part paths are read again, and a track no longer in the album is gone`() = runBlocking {
        val rig = Rig()

        assertEquals(
            "http://plex.test:32400/library/parts/1204/1700000000/file.mp3",
            rig.source.authorized(server, "plex_1101", "1104") { it.url }
        )
        assertFailsWith<DownloadFileGoneException> { rig.source.authorized(server, "plex_1101", "9999") { it } }
        Unit
    }

    @Test
    fun `a track without a file cannot be downloaded`() = runBlocking {
        val rig = Rig(children = tracks.replace("\"key\": \"/library/parts/1204/1700000000/file.mp3\",", "\"key\": \"\","))

        val plan = rig.source.plan(server, "plex_1101")

        assertEquals(PLEX_AUDIO_MISSING_TEXT, plan.failure)
        assertTrue(plan.files.isEmpty())
    }

    @Test
    fun `a size Plex left out is read from the answer to a one-byte request`() = runBlocking {
        val rig = Rig(children = tracks.replace("\"size\": 1000000,", "")) {
            respond("x", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-0/1234567"))
        }

        val plan = rig.source.plan(server, "plex_1101")

        assertEquals(listOf(9_600_000L, 14_400_000L, 1_234_567L), plan.files.map { it.totalBytes })
        val asked = requests.single { it.url.encodedPath.startsWith("/library/parts/") }
        assertEquals("/library/parts/1204/1700000000/file.mp3", asked.url.encodedPath)
        assertEquals("bytes=0-0", asked.headers[HttpHeaders.Range])
        assertEquals("tok", asked.headers["X-Plex-Token"])
        assertNull(asked.url.parameters["X-Plex-Token"])
    }

    @Test
    fun `without any size the book cannot be downloaded`() = runBlocking {
        val rig = Rig(children = tracks.replace("\"size\": 1000000,", "")) { respond("", HttpStatusCode.OK) }

        val plan = rig.source.plan(server, "plex_1101")

        assertEquals(PLEX_SIZE_UNKNOWN_TEXT, plan.failure)
    }

    @Test
    fun `a refused token blanks the sign-in and asks to sign in again`() = runBlocking {
        val rig = Rig(childrenStatus = HttpStatusCode.Unauthorized)

        assertFailsWith<SignInRequiredException> { rig.source.plan(server, "plex_1101") }
        assertEquals(listOf("plex1"), signedOut)
    }

    @Test
    fun `a 401 while fetching a file blanks the sign-in too`() = runBlocking {
        val rig = Rig()
        rig.source.plan(server, "plex_1101")

        assertFailsWith<SignInRequiredException> {
            rig.source.authorized(server, "plex_1101", "1102") { throw HttpStatusException(HttpStatusCode.Unauthorized) }
        }
        assertEquals(listOf("plex1"), signedOut)
    }

    @Test
    fun `a server without a token asks to sign in again before any request`() = runBlocking {
        val rig = Rig()
        rig.servers.save(server.copy(token = ""))

        assertFailsWith<SignInRequiredException> { rig.source.plan(server, "plex_1101") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the lengths Plex knows are given by rating key`() = runBlocking {
        assertEquals(mapOf("1102" to 600_000L, "1103" to 900_000L), Rig(children = recorded).source.fileLengths(server, "plex_1101"))
    }

    @Test
    fun `a book with a track of unknown length is refused before anything is downloaded`() = runBlocking {
        val plan = Rig(children = recorded).source.plan(server, "plex_1101")

        assertEquals(com.chapterplayer.domain.plex.PLEX_LENGTH_UNKNOWN_TEXT, plan.failure)
        assertTrue(plan.files.isEmpty())
        assertTrue(requests.none { it.url.encodedPath.startsWith("/library/parts/") })
    }

    @Test
    fun `a size request without a definite answer is tried again later, not given up`() = runBlocking {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.Found, HttpStatusCode.TooManyRequests)) {
            val rig = Rig(children = tracks.replace("\"size\": 1000000,", "")) {
                respond("", status, headersOf(HttpHeaders.Location, "http://elsewhere.test/x"))
            }
            assertFailsWith<PlexTryLaterException>("status $status") { rig.source.plan(server, "plex_1101") }
        }
    }

    @Test
    fun `a size request for a file the server does not have makes the book not downloadable`() = runBlocking {
        val rig = Rig(children = tracks.replace("\"size\": 1000000,", "")) { respond("", HttpStatusCode.NotFound) }
        assertEquals(PLEX_AUDIO_MISSING_TEXT, rig.source.plan(server, "plex_1101").failure)
    }

    @Test
    fun `a stored address that does not answer while planning re-probes and plans at the new address`() = runBlocking {
        val moved = server.copy(baseUrl = "http://10.0.0.2:32400")
        val seen = mutableListOf<Pair<String, String>>()
        lateinit var rig: Rig
        rig = Rig(deadHost = "plex.test", onConnectionFailure = { used, error ->
            seen += used.baseUrl to error::class.simpleName.orEmpty()
            rig.servers.save(moved)
            moved
        })

        val plan = rig.source.plan(server, "plex_1101")

        assertNull(plan.failure)
        assertEquals(3, plan.files.size)
        assertEquals(listOf("http://plex.test:32400" to "ConnectException"), seen)
        assertEquals("10.0.0.2", requests.last().url.host)
        // A later file request follows the new address too.
        assertEquals("http://10.0.0.2:32400/library/parts/1202/1700000000/file.mp3", rig.source.authorized(server, "plex_1101", "1102") { it.url })
    }

    @Test
    fun `without a new address a planning connection failure is thrown, so the job tries again later`() = runBlocking {
        val rig = Rig(deadHost = "plex.test")
        assertFailsWith<java.net.ConnectException> { rig.source.plan(server, "plex_1101") }
        Unit
    }

    @Test
    fun `a plain http address not verified on this network gets no request with the token`() = runBlocking {
        val lan = server.copy(baseUrl = "http://192.0.2.11:32400")
        val rig = Rig(prepare = { null })
        rig.servers.save(lan)

        assertFailsWith<PlexAddressNotVerifiedException> { rig.source.plan(lan, "plex_1101") }
        assertFailsWith<PlexAddressNotVerifiedException> { rig.source.authorized(lan, "plex_1101", "1102") { it } }
        assertFailsWith<PlexAddressNotVerifiedException> { rig.source.authorizedSmall(lan, "http://192.0.2.11:32400/library/metadata/1/thumb/2") { it } }
        assertFailsWith<PlexAddressNotVerifiedException> { rig.source.fileLengths(lan, "plex_1101") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `requests go to the address the check hands back`() = runBlocking {
        val https = server.copy(baseUrl = "https://192-0-2-11.fixture.plex.direct:32400")
        val rig = Rig(prepare = { https })

        rig.source.plan(server, "plex_1101")

        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.url.host == "192-0-2-11.fixture.plex.direct" && it.url.protocol.name == "https" })
    }
}
