package com.chapterplayer.data.source

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId
import com.chapterplayer.domain.playback.ServerProgress
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinLibrarySourceTest {

    private val booksView = "40fd54c7fab6b3a985f36b461a437081"
    private val otherBooksView = "5c1e0a9b8d7f6e5d4c3b2a1908f7e6d5"
    private val token = "a6f98b2a08d92285b19af86a9503bf50"
    private val server = ServerConfig("row-1", ServerSource.JELLYFIN, "Den", "https://jf.test/jellyfin", token)
    private val requests = mutableListOf<HttpRequestData>()
    private val signedOut = mutableListOf<ServerConfig>()
    private var now = 1_000_000L

    private val twoBooksViews = """{"Items":[
        {"Id":"$booksView","Name":"Audiobooks","CollectionType":"books"},
        {"Id":"$otherBooksView","Name":"Kids Books","CollectionType":"books"},
        {"Id":"10aae6df2f1368214a0e5f52fd20b11a","Name":"Music","CollectionType":"music"}],"TotalRecordCount":3}"""

    private data class Answer(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)

    private fun source(expectedServerId: String? = null, route: (HttpRequestData) -> Answer?): JellyfinLibrarySource {
        val client = HttpClient(MockEngine { request ->
            requests += request
            val a = route(request)
            if (a == null) respond("", HttpStatusCode.NotFound)
            else respond(a.body, a.status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { installApiDefaults(isDebuggable = false) }
        val api = JellyfinApiService(client) { JellyfinClient("Android", it, "0.4.1") }
        return JellyfinLibrarySource(
            api,
            object : InstallId { override val value = "client-1" },
            markSignedOut = { signedOut += it },
            nowMs = { now },
            expectedServerId = { expectedServerId },
        )
    }

    /** One books view with the two fixture pages, the details and the user data. */
    private fun library(views: String = fixture("jellyfin/user-views.json")) = source { r ->
        val p = r.url.encodedPath
        when {
            p.endsWith("/UserViews") -> Answer(views)
            p.endsWith("/Items") -> when (r.url.parameters["StartIndex"]) {
                "0" -> Answer(fixture("jellyfin/items-audiobooks-page-1.json"))
                "2" -> Answer(fixture("jellyfin/items-audiobooks-page-2.json"))
                else -> null
            }
            p.endsWith("/UserData") -> Answer(fixture("jellyfin/user-data-in-progress.json"))
            p.contains("/Items/") -> Answer(fixture("jellyfin/item-detail-chapters.json"))
            else -> null
        }
    }

    @Test
    fun `two pages give three books`() = runTest {
        val books = library().fetchBooks(server)
        assertEquals(3, books.size)
        assertEquals(listOf("The Harbour Lights", "A Field of Stones", "Quiet Rivers"), books.map { it.title })
        assertTrue(books.all { it.id.startsWith("jf_") && it.source == ServerSource.JELLYFIN && it.serverId == "row-1" })
        assertTrue(books.all { it.libraryId == booksView })
        assertEquals("Cy Voice, Di Tone", books[1].narrator)
    }

    @Test
    fun `every call sends the row's device id and the token in the header only`() = runTest {
        library().fetchSnapshot(server)
        val deviceId = jellyfinDeviceId("client-1", "row-1")
        assertTrue(requests.isNotEmpty())
        for (r in requests) {
            val auth = r.headers[HttpHeaders.Authorization].orEmpty()
            assertTrue(auth.contains("DeviceId=\"$deviceId\"") && auth.contains("Token=\"$token\""), auth)
            assertTrue(!r.url.toString().contains(token))
            assertTrue(r.url.encodedPath.startsWith("/jellyfin/"))
        }
    }

    @Test
    fun `libraries are the books views`() = runTest {
        val snapshot = library(twoBooksViews).fetchSnapshot(server)
        assertEquals(
            listOf(LibraryInfo("row-1", booksView, "Audiobooks"), LibraryInfo("row-1", otherBooksView, "Kids Books")),
            snapshot.libraries,
        )
        assertEquals(emptyList(), snapshot.collections)
        assertEquals(listOf(LibraryInfo("row-1", booksView, "Audiobooks")), library().fetchLibraries(server))
    }

    @Test
    fun `a view that fails is reported while the other view's books come`() = runTest {
        val src = source { r ->
            val p = r.url.encodedPath
            when {
                p.endsWith("/UserViews") -> Answer(twoBooksViews)
                p.endsWith("/Items") && r.url.parameters["ParentId"] == otherBooksView -> Answer("{}", HttpStatusCode.InternalServerError)
                p.endsWith("/Items") -> Answer(fixture("jellyfin/items-audiobooks-page-2.json").replace("\"StartIndex\": 2", "\"StartIndex\": 0")
                    .replace("\"TotalRecordCount\": 3", "\"TotalRecordCount\": 1"))
                else -> null
            }
        }
        val snapshot = src.fetchSnapshot(server)
        assertEquals(listOf("Quiet Rivers"), snapshot.books.map { it.title })
        assertEquals(setOf(otherBooksView), snapshot.failedLibraries.keys)
        assertTrue(snapshot.failedLibraries.getValue(otherBooksView).startsWith("Kids Books: "))
        assertTrue(!snapshot.failedLibraries.values.single().contains(token))
    }

    @Test
    fun `a failing view is reported in fixed words, never the server's text`() = runTest {
        val src = source { r ->
            val p = r.url.encodedPath
            when {
                p.endsWith("/UserViews") -> Answer(twoBooksViews)
                p.endsWith("/Items") && r.url.parameters["ParentId"] == otherBooksView ->
                    Answer("""{"Items": "<html>private page text</html>"}""")
                p.endsWith("/Items") -> Answer(fixture("jellyfin/items-audiobooks-page-1.json"))
                else -> null
            }
        }
        val text = src.fetchSnapshot(server).failedLibraries.getValue(otherBooksView)
        assertEquals("Kids Books: the server's answer could not be read", text)
    }

    @Test
    fun `every view failing on an unreadable answer fails in fixed words`() = runTest {
        val src = source { r ->
            if (r.url.encodedPath.endsWith("/UserViews")) Answer(twoBooksViews) else Answer("""{"Items": "<html>private</html>"}""")
        }
        val e = assertFailsWith<IllegalStateException> { src.fetchSnapshot(server) }
        assertEquals("The server's answer could not be read", e.message)
    }

    @Test
    fun `a view answering an error status is reported with the status`() = runTest {
        val src = source { r ->
            val p = r.url.encodedPath
            when {
                p.endsWith("/UserViews") -> Answer(twoBooksViews)
                p.endsWith("/Items") && r.url.parameters["ParentId"] == otherBooksView -> Answer("oops <b>secret</b>", HttpStatusCode.BadGateway)
                p.endsWith("/Items") -> Answer(fixture("jellyfin/items-audiobooks-page-1.json"))
                else -> null
            }
        }
        assertEquals("Kids Books: the server answered with HTTP 502", src.fetchSnapshot(server).failedLibraries.getValue(otherBooksView))
    }

    @Test
    fun `a sync first checks the address still belongs to this server`() = runTest {
        val other = source(expectedServerId = "11111111111111111111111111111111") { r ->
            if (r.url.encodedPath.endsWith("/System/Info/Public")) Answer(fixture("jellyfin/system-info-public.json")) else Answer("{}")
        }
        val e = assertFailsWith<IllegalStateException> { other.fetchSnapshot(server) }
        assertEquals("This address now belongs to another Jellyfin server. Remove this server and add it again.", e.message)
        // Only the public info was asked, with no token.
        assertEquals(listOf("/jellyfin/System/Info/Public"), requests.map { it.url.encodedPath })
        assertNull(requests.single().headers[HttpHeaders.Authorization])
        assertTrue(signedOut.isEmpty())
    }

    /** A server row with a kept id whose address answers [publicInfo] there; every other call answers normally. */
    private fun checkedSource(publicInfo: () -> Answer) = source(expectedServerId = "36b7674f7149d16771400fae9b9eb1f9") { r ->
        val p = r.url.encodedPath
        when {
            p.endsWith("/System/Info/Public") -> publicInfo()
            p.endsWith("/UserViews") -> Answer(fixture("jellyfin/user-views.json"))
            p.endsWith("/Items") -> Answer(fixture("jellyfin/items-folder-of-parts.json"))
            else -> null
        }
    }

    private suspend fun assertStoppedBeforeToken(src: JellyfinLibrarySource) {
        val e = assertFailsWith<IllegalStateException> { src.fetchSnapshot(server) }
        assertEquals(com.chapterplayer.domain.jellyfin.JELLYFIN_NOT_THIS_SERVER_TEXT, e.message)
        assertEquals(listOf("/jellyfin/System/Info/Public"), requests.map { it.url.encodedPath })
        assertTrue(requests.none { it.headers[HttpHeaders.Authorization] != null })
        assertTrue(signedOut.isEmpty())
    }

    @Test
    fun `a web page at the address stops the sync before the token is sent`() = runTest {
        assertStoppedBeforeToken(checkedSource { Answer("<html><body>Router login</body></html>") })
    }

    @Test
    fun `a 404 at the address stops the sync before the token is sent`() = runTest {
        assertStoppedBeforeToken(checkedSource { Answer("not here", HttpStatusCode.NotFound) })
    }

    @Test
    fun `a server error at the address stops the sync before the token is sent`() = runTest {
        assertStoppedBeforeToken(checkedSource { Answer("oops", HttpStatusCode.ServiceUnavailable) })
    }

    @Test
    fun `another app's JSON at the address stops the sync before the token is sent`() = runTest {
        assertStoppedBeforeToken(checkedSource { Answer("""{"name":"nas","version":"7.2"}""") })
        requests.clear()
        assertStoppedBeforeToken(checkedSource { Answer("""{"ProductName":"Jellyfin Server","Version":"12.4.0"}""") })
    }

    @Test
    fun `an unreachable address skips the check and the sync goes on as usual`() = runTest {
        val src = source(expectedServerId = "36b7674f7149d16771400fae9b9eb1f9") { r ->
            val p = r.url.encodedPath
            when {
                p.endsWith("/System/Info/Public") -> throw java.net.ConnectException("refused")
                p.endsWith("/UserViews") -> Answer(fixture("jellyfin/user-views.json"))
                p.endsWith("/Items") -> Answer(fixture("jellyfin/items-folder-of-parts.json"))
                else -> null
            }
        }
        assertEquals(2, src.fetchSnapshot(server).books.size)
    }

    @Test
    fun `an unreadable views answer fails in fixed words`() = runTest {
        val src = source { r ->
            if (r.url.encodedPath.endsWith("/UserViews")) Answer("""{"Items": "<html>private page text</html>"}""") else null
        }
        val e = assertFailsWith<IllegalStateException> { src.fetchSnapshot(server) }
        assertEquals("The server's answer could not be read", e.message)
    }

    @Test
    fun `a sync on the same server goes ahead after the check`() = runTest {
        val src = source(expectedServerId = "36B7674F7149D16771400FAE9B9EB1F9") { r ->
            val p = r.url.encodedPath
            when {
                p.endsWith("/System/Info/Public") -> Answer(fixture("jellyfin/system-info-public.json"))
                p.endsWith("/UserViews") -> Answer(fixture("jellyfin/user-views.json"))
                p.endsWith("/Items") -> Answer(fixture("jellyfin/items-folder-of-parts.json"))
                else -> null
            }
        }
        assertEquals(2, src.fetchSnapshot(server).books.size)
        assertEquals("/jellyfin/System/Info/Public", requests.first().url.encodedPath)
    }

    @Test
    fun `every view failing fails the sync`() = runTest {
        val src = source { r ->
            if (r.url.encodedPath.endsWith("/UserViews")) Answer(twoBooksViews) else Answer("{}", HttpStatusCode.InternalServerError)
        }
        assertFailsWith<Exception> { src.fetchSnapshot(server) }
    }

    @Test
    fun `a turned off view is not read`() = runTest {
        val src = library(twoBooksViews)
        val snapshot = src.fetchSnapshot(server.copy(excludedLibraryIds = setOf(otherBooksView)))
        assertEquals(3, snapshot.books.size)
        assertTrue(requests.none { it.url.parameters["ParentId"] == otherBooksView })
        // The library list still names it, so the user can turn it back on.
        assertEquals(2, snapshot.libraries!!.size)
    }

    @Test
    fun `a server with no books view says so`() = runTest {
        val noBooks = """{"Items":[{"Id":"10aae6df2f1368214a0e5f52fd20b11a","Name":"Music","CollectionType":"music"}]}"""
        val e = assertFailsWith<IllegalStateException> { library(noBooks).fetchSnapshot(server) }
        assertEquals("The server has no Books library. Create one with the type Books.", e.message)
    }

    @Test
    fun `the folder of parts passes through as separate books`() = runTest {
        val src = source { r ->
            when {
                r.url.encodedPath.endsWith("/UserViews") -> Answer(fixture("jellyfin/user-views.json"))
                r.url.encodedPath.endsWith("/Items") -> Answer(fixture("jellyfin/items-folder-of-parts.json"))
                else -> null
            }
        }
        val books = src.fetchBooks(server)
        assertEquals(listOf("Part 01", "Part 02"), books.map { it.title })
        assertEquals(2, books.map { it.id }.toSet().size)
    }

    @Test
    fun `book details give chapters and description`() = runTest {
        val details = assertNotNull(library().fetchBookDetails(server, "jf_0bdd952d73af33d8d4421dfdb2f25919"))
        assertEquals(
            listOf("Opening Credits", "Chapter 1", "Chapter 2", "Chapter 3"),
            details.chapters.map { it.title },
        )
        assertEquals(Chapter(3, "Chapter 3", 8 * 3_600_000L, 10 * 3_600_000L), details.chapters.last())
        assertEquals("A synthetic description of The Harbour Lights.", details.description)
        assertEquals(listOf("Fiction"), details.genres)
        assertEquals("2021", details.publishedYear)
        assertEquals("Harbour Tales", details.series.single().name)
        assertEquals(10 * 3_600_000L, details.durationMs)
        assertTrue(requests.single().url.encodedPath.endsWith("/Items/0bdd952d73af33d8d4421dfdb2f25919"))
    }

    @Test
    fun `a book without chapters gets one part`() = runTest {
        val src = source { Answer(fixture("jellyfin/item-detail-no-chapters.json")) }
        val details = assertNotNull(src.fetchBookDetails(server, "jf_3a68b8d5c009572d209b2205cfc1bda8"))
        assertEquals(listOf("Part 1"), details.chapters.map { it.title })
        assertNull(details.series.firstOrNull())
    }

    @Test
    fun `a refused token blanks the sign-in and asks to sign in again`() = runTest {
        val src = source { Answer("", HttpStatusCode.Unauthorized) }
        assertFailsWith<SignInRequiredException> { src.fetchBooks(server) }
        assertEquals(listOf("row-1"), signedOut.map { it.id })
    }

    @Test
    fun `progress is read from the user data`() = runTest {
        val src = library()
        src.fetchSnapshot(server)
        val progress = src.fetchProgress(server, "jf_0bdd952d73af33d8d4421dfdb2f25919")
        assertEquals(ServerProgress(1_350_000L, 10 * 3_600_000L, false, 1_792_485_000_000L), progress)
        assertTrue(requests.last().url.encodedPath.endsWith("/UserItems/0bdd952d73af33d8d4421dfdb2f25919/UserData"))
    }

    @Test
    fun `progress of a book not synced yet reads its length`() = runTest {
        val src = library()
        val progress = src.fetchProgress(server, "jf_0bdd952d73af33d8d4421dfdb2f25919")
        assertEquals(10 * 3_600_000L, progress?.durationMs)
    }

    @Test
    fun `progress that is zero and not played is none`() = runTest {
        val src = source { Answer(fixture("jellyfin/user-data-zero.json")) }
        assertNull(src.fetchProgress(server, "jf_3a68b8d5c009572d209b2205cfc1bda8"))
    }

    @Test
    fun `all progress after a snapshot makes no further request`() = runTest {
        val src = library()
        src.fetchSnapshot(server)
        val count = requests.size
        now += 59_000
        val all = src.fetchAllProgress(server)
        assertEquals(count, requests.size)
        // The book in progress and the finished one; the one with no listening has no progress.
        assertEquals(setOf("jf_0bdd952d73af33d8d4421dfdb2f25919", "jf_26f699bf533c76bf809909fd468f32e9"), all.keys)
        assertEquals(true, all.getValue("jf_26f699bf533c76bf809909fd468f32e9").isFinished)
    }

    @Test
    fun `all progress reads again when the snapshot is old`() = runTest {
        val src = library()
        src.fetchSnapshot(server)
        val count = requests.size
        now += 61_000
        val all = src.fetchAllProgress(server)
        assertTrue(requests.size > count)
        assertEquals(2, all.size)
    }

    @Test
    fun `the snapshot cache is per server`() = runTest {
        val src = library()
        src.fetchSnapshot(server)
        val count = requests.size
        src.fetchAllProgress(server.copy(id = "row-2"))
        assertTrue(requests.size > count)
    }

    // ---- playback ----

    @Test
    fun `an m4b starts as one track with its chapters at the server's place`() = runTest {
        val src = source { Answer(fixture("jellyfin/item-detail-media-sources.json")) }
        val session = src.startPlayback(server, "jf_0bdd952d73af33d8d4421dfdb2f25919")

        val track = session.tracks.single()
        assertEquals(0, track.index)
        assertEquals(0L, track.startMs)
        assertEquals(10 * 3_600_000L, track.durationMs)
        assertEquals("audio/mp4", track.mimeType)
        assertEquals("0bdd952d73af33d8d4421dfdb2f25919", track.fileId)
        assertTrue(track.url.startsWith("https://jf.test/jellyfin/Audio/0bdd952d73af33d8d4421dfdb2f25919/stream?static=true&mediaSourceId=0bdd952d73af33d8d4421dfdb2f25919&playSessionId="), track.url)
        assertTrue(!track.url.contains(token))
        assertEquals(4, session.chapters.size)
        assertEquals(1_350_000L, session.startPositionMs)
        assertEquals(10 * 3_600_000L, session.durationMs)
        assertEquals("row-1", session.serverId)
        assertEquals("jf_0bdd952d73af33d8d4421dfdb2f25919", session.bookId)
        assertTrue(session.sessionId.startsWith("jf-0bdd952d73af33d8d4421dfdb2f25919-"), session.sessionId)
        assertEquals("/jellyfin/Items/0bdd952d73af33d8d4421dfdb2f25919", requests.single().url.encodedPath)
        assertEquals("Chapters,People,Overview,Genres,Studios,MediaSources", requests.single().url.parameters["Fields"])
    }

    @Test
    fun `two starts get two sessions and two play session ids`() = runTest {
        val src = source { Answer(fixture("jellyfin/item-detail-media-sources.json")) }
        val one = src.startPlayback(server, "jf_0bdd952d73af33d8d4421dfdb2f25919")
        val two = src.startPlayback(server, "jf_0bdd952d73af33d8d4421dfdb2f25919")
        assertTrue(one.sessionId != two.sessionId)
        assertTrue(one.tracks.single().url != two.tracks.single().url)
    }

    @Test
    fun `a finished book starts at the beginning`() = runTest {
        val played = fixture("jellyfin/item-detail-media-sources.json")
            .replace("\"PlaybackPositionTicks\": 13500000000", "\"PlaybackPositionTicks\": 0")
            .replace("\"Played\": false", "\"Played\": true")
        val src = source { Answer(played) }
        assertEquals(0L, src.startPlayback(server, "jf_0bdd952d73af33d8d4421dfdb2f25919").startPositionMs)
    }

    @Test
    fun `start without media source fails with a safe text`() = runTest {
        val src = source { Answer(fixture("jellyfin/item-detail-chapters.json")) }
        val e = assertFailsWith<com.chapterplayer.data.playback.PlaybackStartException> {
            src.startPlayback(server, "jf_0bdd952d73af33d8d4421dfdb2f25919")
        }
        assertEquals(com.chapterplayer.domain.jellyfin.JELLYFIN_NO_AUDIO_TEXT, e.message)
        assertTrue(!e.message!!.contains("jf.test") && !e.message!!.contains(token))
    }

    @Test
    fun `a container the app cannot play fails with the format text`() = runTest {
        val wma = fixture("jellyfin/item-detail-media-sources.json")
            .replace("\"Container\": \"mov,mp4,m4a,3gp,3g2,mj2\"", "\"Container\": \"asf\"")
        assertTrue(wma.contains("\"asf\""))
        val src = source { Answer(wma) }
        val e = assertFailsWith<com.chapterplayer.data.playback.PlaybackStartException> {
            src.startPlayback(server, "jf_0bdd952d73af33d8d4421dfdb2f25919")
        }
        assertEquals(com.chapterplayer.data.playback.UNSUPPORTED_AUDIO_TEXT, e.message)
    }

    @Test
    fun `an ogg book starts on android and is refused by the apple gate`() = runTest {
        val src = source { Answer(fixture("jellyfin/item-detail-ogg.json")) }
        val track = src.startPlayback(server, "jf_e68bb4f5169289c83051faa19260e55d").tracks.single()
        assertEquals("audio/ogg", track.mimeType)
        assertTrue(!com.chapterplayer.data.playback.applePlays(track))
    }

    @Test
    fun `a refused token on start blanks the sign-in`() = runTest {
        val src = source { Answer("", HttpStatusCode.Unauthorized) }
        assertFailsWith<SignInRequiredException> { src.startPlayback(server, "jf_0bdd952d73af33d8d4421dfdb2f25919") }
        assertEquals(listOf("row-1"), signedOut.map { it.id })
    }
}
