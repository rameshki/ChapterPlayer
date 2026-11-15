package com.chapterplayer.data.source

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinTimeMs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The live session of a Jellyfin book (J2): session reports, the band writes, the close and the finished pin. */
class JellyfinLiveSessionTest {

    private val itemId = "0bdd952d73af33d8d4421dfdb2f25919"
    private val bookId = "jf_$itemId"
    private val token = "a6f98b2a08d92285b19af86a9503bf50"
    private val server = ServerConfig("row-1", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", token)
    private val requests = mutableListOf<HttpRequestData>()
    private val signedOut = mutableListOf<ServerConfig>()
    private val now = 1_793_000_000_000L
    private val hour = 3_600_000L
    private val book = 10 * hour

    private class Answer(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)

    /** The item with its media source; [extra] answers anything else first (null = the default answers). */
    private fun source(extra: (HttpRequestData) -> Answer? = { null }): JellyfinLibrarySource {
        val client = HttpClient(MockEngine { request ->
            requests += request
            val p = request.url.encodedPath
            val a = extra(request) ?: when {
                p.endsWith("/UserViews") -> Answer(fixture("jellyfin/user-views.json"))
                p.endsWith("/Items") -> Answer(fixture("jellyfin/items-audiobooks-page-2.json")
                    .replace("\"StartIndex\": 2", "\"StartIndex\": 0").replace("\"TotalRecordCount\": 3", "\"TotalRecordCount\": 1"))
                p.endsWith("/System/Configuration") -> Answer(fixture("jellyfin/system-configuration.json"))
                p.endsWith("/Items/$itemId") -> Answer(fixture("jellyfin/item-detail-media-sources.json"))
                p.endsWith("/UserData") -> Answer(fixture("jellyfin/user-data-in-progress.json"))
                p.contains("/Sessions/Playing") -> Answer("")
                p.contains("/UserPlayedItems/") -> Answer(fixture("jellyfin/user-played-items.json"))
                else -> null
            }
            if (a == null) respond("", HttpStatusCode.NotFound)
            else respond(a.body, a.status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { installApiDefaults(isDebuggable = false) }
        return JellyfinLibrarySource(
            JellyfinApiService(client) { JellyfinClient("Android", it, "0.4.1") },
            object : InstallId { override val value = "client-1" },
            markSignedOut = { signedOut += it },
            nowMs = { now },
        )
    }

    private suspend fun JellyfinLibrarySource.open(): PlaybackSession = startPlayback(server, bookId).also { requests.clear() }

    private fun bodyOf(r: HttpRequestData): JsonObject {
        val text = when (val c = r.body) {
            is TextContent -> c.text
            is OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> error("unexpected body ${c::class}")
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun paths() = requests.map { it.url.encodedPath.removePrefix("/jellyfin") }

    private fun JsonObject.ticks(name: String) = getValue(name).jsonPrimitive.long

    @Test
    fun `the first report starts the session, later ones report progress`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        src.syncPlayback(server, s.sessionId, hour + 10_000L, 10_000L, book, isPlaying = true)

        assertEquals(listOf("/Sessions/Playing", "/UserItems/$itemId/UserData", "/Sessions/Playing/Progress"), paths())
        val start = bodyOf(requests[0])
        assertEquals(itemId, start.getValue("ItemId").jsonPrimitive.content)
        assertEquals(itemId, start.getValue("MediaSourceId").jsonPrimitive.content)
        val playSession = start.getValue("PlaySessionId").jsonPrimitive.content
        assertTrue(s.tracks.single().url.endsWith("playSessionId=$playSession"))
        assertEquals(hour * 10_000L, start.ticks("PositionTicks"))
        assertEquals(true, start.getValue("CanSeek").jsonPrimitive.boolean)
        assertEquals("DirectPlay", start.getValue("PlayMethod").jsonPrimitive.content)
        assertEquals(false, start.getValue("IsPaused").jsonPrimitive.boolean)

        val progress = bodyOf(requests[2])
        assertEquals((hour + 10_000L) * 10_000L, progress.ticks("PositionTicks"))
        assertEquals(false, progress.getValue("IsPaused").jsonPrimitive.boolean)
        assertEquals("timeupdate", progress.getValue("EventName").jsonPrimitive.content)
        assertEquals(playSession, progress.getValue("PlaySessionId").jsonPrimitive.content)
        assertTrue(requests.all { it.method == HttpMethod.Post })
    }

    @Test
    fun `a two minute position adds the user data write after the progress report`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, 60_000L, 10_000L, book, isPlaying = true)
        requests.clear()
        src.syncPlayback(server, s.sessionId, 120_000L, 10_000L, book, isPlaying = true)

        assertEquals(listOf("/Sessions/Playing/Progress", "/UserItems/$itemId/UserData"), paths())
        val write = bodyOf(requests[1])
        assertEquals(120_000L * 10_000L, write.ticks("PlaybackPositionTicks"))
        assertEquals(false, write.getValue("Played").jsonPrimitive.boolean)
        assertEquals(now, jellyfinTimeMs(write.getValue("LastPlayedDate").jsonPrimitive.content))
    }

    @Test
    fun `the last minutes add the user data write too`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        src.syncPlayback(server, s.sessionId, book - 120_000L, 10_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing/Progress", "/UserItems/$itemId/UserData"), paths())
    }

    @Test
    fun `a paused report always writes`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        src.syncPlayback(server, s.sessionId, hour + 5_000L, 5_000L, book, isPlaying = false)

        assertEquals(listOf("/Sessions/Playing/Progress", "/UserItems/$itemId/UserData"), paths())
        val pause = bodyOf(requests[0])
        assertEquals(true, pause.getValue("IsPaused").jsonPrimitive.boolean)
        assertEquals("pause", pause.getValue("EventName").jsonPrimitive.content)
        assertEquals((hour + 5_000L) * 10_000L, bodyOf(requests[1]).ticks("PlaybackPositionTicks"))
    }

    @Test
    fun `a first report made on a pause starts the session and keeps the pause place`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 5_000L, book, isPlaying = false)
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/UserItems/$itemId/UserData"), paths())
        assertEquals(true, bodyOf(requests[1]).getValue("IsPaused").jsonPrimitive.boolean)
    }

    @Test
    fun `closing sends the stop with its position`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        src.closePlayback(server, s.sessionId, 2 * hour, 0L)

        assertEquals(listOf("/Sessions/Playing/Stopped"), paths())
        val stop = bodyOf(requests.single())
        assertEquals(2 * hour * 10_000L, stop.ticks("PositionTicks"))
        assertFalse(stop.containsKey("Failed"))
    }

    @Test
    fun `closePlayback never sends a stop without its position`() = runTest {
        val src = source()
        for (position in listOf(0L, 90_000L, hour, book - 60_000L, book)) {
            val s = src.open()
            src.syncPlayback(server, s.sessionId, 30_000L, 10_000L, book, isPlaying = true)
            requests.clear()
            src.closePlayback(server, s.sessionId, position, 0L)
            val stops = requests.filter { it.url.encodedPath.endsWith("/Sessions/Playing/Stopped") }
            assertEquals(1, stops.size, "$position")
            assertTrue(bodyOf(stops.single()).containsKey("PositionTicks"), "$position")
        }
    }

    @Test
    fun `closing in a band writes the place after the stop`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        src.closePlayback(server, s.sessionId, book - 3 * 60_000L, 0L)
        assertEquals(listOf("/Sessions/Playing/Stopped", "/UserItems/$itemId/UserData"), paths())
        assertEquals((book - 3 * 60_000L) * 10_000L, bodyOf(requests[1]).ticks("PlaybackPositionTicks"))
    }

    @Test
    fun `the real end stops at the end and writes nothing`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, book - 30_000L, 10_000L, book, isPlaying = true)
        requests.clear()
        src.closePlayback(server, s.sessionId, book - 400L, 0L)
        assertEquals(listOf("/Sessions/Playing/Stopped"), paths())
        assertEquals(book * 10_000L, bodyOf(requests.single()).ticks("PositionTicks"))
    }

    @Test
    fun `a session that never reported closes silently`() = runTest {
        val src = source()
        val s = src.open()
        src.closePlayback(server, s.sessionId, hour, 0L)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a closed session is forgotten`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        src.closePlayback(server, s.sessionId, hour, 0L)
        assertFailsWith<IllegalStateException> { src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true) }
    }

    @Test
    fun `after the book is set finished the session sends nothing`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        try {
            src.pushProgress(server, bookId, book, book, isFinished = true)
        } catch (e: Exception) {
            // Task 11 only pins the session; the push itself is Task 12's.
        }
        requests.clear()
        src.syncPlayback(server, s.sessionId, hour + 10_000L, 10_000L, book, isPlaying = true)
        src.syncPlayback(server, s.sessionId, hour + 15_000L, 5_000L, book, isPlaying = false)
        src.closePlayback(server, s.sessionId, hour + 15_000L, 0L)
        assertTrue(requests.isEmpty(), paths().toString())
    }

    @Test
    fun `set not finished again, the session reports again`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        runCatching { src.pushProgress(server, bookId, book, book, isFinished = true) }
        runCatching { src.pushProgress(server, bookId, 0L, book, isFinished = false) }
        requests.clear()
        src.syncPlayback(server, s.sessionId, hour + 10_000L, 10_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing/Progress"), paths())
    }

    @Test
    fun `an unknown session throws so the position is queued`() = runTest {
        val src = source()
        assertFailsWith<IllegalStateException> { src.syncPlayback(server, "jf-$itemId-99", hour, 10_000L, book, isPlaying = true) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `thresholds read with the sync are used`() = runTest {
        val src = source()
        src.fetchSnapshot(server)
        assertTrue(requests.any { it.url.encodedPath == "/jellyfin/System/Configuration" })
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        // The fixture says 2 min / 10 min: 3 min in is outside the start band, 9 min left is inside the end band.
        requests.clear()
        src.syncPlayback(server, s.sessionId, 3 * 60_000L, 10_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing/Progress"), paths())
        requests.clear()
        src.syncPlayback(server, s.sessionId, book - 9 * 60_000L, 10_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing/Progress", "/UserItems/$itemId/UserData"), paths())
    }

    @Test
    fun `an unreadable configuration means five and five and does not stop the sync`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            requests.clear()
            val src = source { r -> if (r.url.encodedPath.endsWith("/System/Configuration")) Answer("", status) else null }
            assertEquals(1, src.fetchSnapshot(server).books.size)
            // A configuration a plain user may not read is not a refused sign-in.
            assertTrue(signedOut.isEmpty(), "$status")
            val s = src.open()
            src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
            requests.clear()
            src.syncPlayback(server, s.sessionId, 4 * 60_000L, 10_000L, book, isPlaying = true)
            assertEquals(listOf("/Sessions/Playing/Progress", "/UserItems/$itemId/UserData"), paths(), "$status")
            requests.clear()
            src.syncPlayback(server, s.sessionId, book - 6 * 60_000L, 10_000L, book, isPlaying = true)
            assertEquals(listOf("/Sessions/Playing/Progress"), paths(), "$status")
        }
    }

    @Test
    fun `a refused token on a report blanks the sign-in`() = runTest {
        val src = source { r -> if (r.url.encodedPath.contains("/Sessions/")) Answer("", HttpStatusCode.Unauthorized) else null }
        val s = src.open()
        assertFailsWith<SignInRequiredException> { src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true) }
        assertEquals(listOf("row-1"), signedOut.map { it.id })
    }

    @Test
    fun `a forbidden report is an error the queue backs off on, not a sign-out`() = runTest {
        val src = source { r -> if (r.url.encodedPath.contains("/Sessions/")) Answer("", HttpStatusCode.Forbidden) else null }
        val s = src.open()
        val e = assertFailsWith<Exception> { src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true) }
        assertEquals("HttpStatusException", e::class.simpleName)
        assertTrue(signedOut.isEmpty())
    }

    @Test
    fun `a failed start report is sent again with the next report`() = runTest {
        var fail = true
        val src = source { r ->
            if (fail && r.url.encodedPath.endsWith("/Sessions/Playing")) Answer("", HttpStatusCode.ServiceUnavailable) else null
        }
        val s = src.open()
        assertFailsWith<Exception> { src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true) }
        fail = false
        requests.clear()
        src.syncPlayback(server, s.sessionId, hour + 10_000L, 10_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing", "/UserItems/$itemId/UserData"), paths())
    }

    @Test
    fun `no request url carries the token`() = runTest {
        val src = source()
        src.fetchSnapshot(server)
        val s = src.startPlayback(server, bookId)
        src.syncPlayback(server, s.sessionId, 60_000L, 10_000L, book, isPlaying = true)
        src.syncPlayback(server, s.sessionId, 70_000L, 10_000L, book, isPlaying = false)
        src.closePlayback(server, s.sessionId, 70_000L, 0L)
        assertTrue(requests.size >= 6)
        for (r in requests) {
            assertFalse(r.url.toString().contains(token), r.url.toString())
            assertTrue(r.headers[HttpHeaders.Authorization].orEmpty().contains("Token=\"$token\""))
        }
    }

    // ---- review fixes: the end, the close and the start write ----

    @Test
    fun `the start always writes the exact place`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing", "/UserItems/$itemId/UserData"), paths())
        val write = bodyOf(requests[1])
        assertEquals(hour * 10_000L, write.ticks("PlaybackPositionTicks"))
        assertEquals(false, write.getValue("Played").jsonPrimitive.boolean)
    }

    @Test
    fun `paused at the end then closed stops at the end once and never writes not played`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        src.syncPlayback(server, s.sessionId, book - 500L, 5_000L, book, isPlaying = false)
        assertEquals(listOf("/Sessions/Playing/Stopped"), paths())
        assertEquals(book * 10_000L, bodyOf(requests.single()).ticks("PositionTicks"))
        requests.clear()
        // Later reports at the end and the close send nothing more.
        src.syncPlayback(server, s.sessionId, book, 0L, book, isPlaying = false)
        src.closePlayback(server, s.sessionId, book - 500L, 0L)
        assertTrue(requests.isEmpty(), paths().toString())
    }

    @Test
    fun `closed at the end then a pause report at the end is queued, not sent`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        src.closePlayback(server, s.sessionId, book - 500L, 0L)
        assertEquals(listOf("/Sessions/Playing/Stopped"), paths())
        assertEquals(book * 10_000L, bodyOf(requests.single()).ticks("PositionTicks"))
        requests.clear()
        assertFailsWith<IllegalStateException> { src.syncPlayback(server, s.sessionId, book - 500L, 0L, book, isPlaying = false) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a first report at the end stops at the end without a not played write`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, book - 200L, 5_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Stopped"), paths())
        assertTrue(requests.none { it.url.encodedPath.endsWith("/UserData") })
    }

    @Test
    fun `playing again from before the end reports again`() = runTest {
        val src = source()
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        src.syncPlayback(server, s.sessionId, book - 500L, 5_000L, book, isPlaying = false)
        requests.clear()
        src.syncPlayback(server, s.sessionId, 2 * hour, 10_000L, book, isPlaying = true)
        assertEquals(listOf("/Sessions/Playing", "/UserItems/$itemId/UserData"), paths())
    }

    @Test
    fun `a failed write after the stop says the place was not saved`() = runTest {
        val src = source { r -> if (r.url.encodedPath.endsWith("/UserData") && r.method == HttpMethod.Post) Answer("", HttpStatusCode.ServiceUnavailable) else null }
        val s = src.open()
        // The start's own write fails too; the start report itself went through.
        runCatching { src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true) }
        requests.clear()
        val e = assertFailsWith<PlaceNotSavedException> { src.closePlayback(server, s.sessionId, book - 3 * 60_000L, 0L) }
        assertEquals(listOf("/Sessions/Playing/Stopped", "/UserItems/$itemId/UserData"), paths())
        assertFalse(e.message.orEmpty().contains(token))
        assertFalse(e.message.orEmpty().contains("jf.test"))
    }

    @Test
    fun `a failed stop says the place was not saved`() = runTest {
        val src = source { r -> if (r.url.encodedPath.endsWith("/Stopped")) Answer("", HttpStatusCode.ServiceUnavailable) else null }
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        assertFailsWith<PlaceNotSavedException> { src.closePlayback(server, s.sessionId, 2 * hour, 0L) }
    }

    @Test
    fun `a report waiting for the lock while the session closes is queued, not reopened`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var block = false
        val src = source { r ->
            if (block && r.url.encodedPath.endsWith("/Sessions/Playing/Progress")) {
                block = false
                entered.complete(Unit)
                kotlinx.coroutines.runBlocking { release.await() }
            }
            null
        }
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        block = true
        // Report 1 holds the session's lock inside its request.
        val first = async { src.syncPlayback(server, s.sessionId, hour + 10_000L, 10_000L, book, isPlaying = true) }
        entered.await()
        // The close forgets the session and waits for the lock.
        val close = async { src.closePlayback(server, s.sessionId, hour + 20_000L, 0L) }
        runCurrent()
        // A report that found the session gone throws at once (queued by the caller).
        assertFailsWith<IllegalStateException> { src.syncPlayback(server, s.sessionId, hour + 15_000L, 5_000L, book, isPlaying = true) }
        release.complete(Unit)
        first.await()
        close.await()
        assertEquals(listOf("/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), paths())
    }

    @Test
    fun `a report made while the close is in flight throws`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var block = false
        val src = source { r ->
            if (block && r.url.encodedPath.endsWith("/Sessions/Playing/Stopped")) {
                block = false
                entered.complete(Unit)
                kotlinx.coroutines.runBlocking { release.await() }
            }
            null
        }
        val s = src.open()
        src.syncPlayback(server, s.sessionId, hour, 10_000L, book, isPlaying = true)
        requests.clear()
        block = true
        val close = async { src.closePlayback(server, s.sessionId, hour + 20_000L, 0L) }
        entered.await()
        // The close holds the lock (its stop is in flight) and the session is already forgotten: a late report throws.
        assertFailsWith<IllegalStateException> { src.syncPlayback(server, s.sessionId, hour + 25_000L, 5_000L, book, isPlaying = true) }
        release.complete(Unit)
        close.await()
        assertEquals(listOf("/Sessions/Playing/Stopped"), paths())
    }
}
