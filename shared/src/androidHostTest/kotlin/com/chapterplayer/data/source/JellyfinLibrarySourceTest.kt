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

    private fun source(route: (HttpRequestData) -> Answer?): JellyfinLibrarySource {
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
}
