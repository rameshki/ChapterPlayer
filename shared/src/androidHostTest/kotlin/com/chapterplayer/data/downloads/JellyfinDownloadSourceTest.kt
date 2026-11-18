package com.chapterplayer.data.downloads

import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.domain.jellyfin.JELLYFIN_NO_AUDIO_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_LENGTH_UNKNOWN_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_SIZE_UNKNOWN_TEXT
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinBookId
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [JellyfinDownloadSource] over the answers recorded from a real Jellyfin server (`fixtures/jellyfin/recorded`). */
class JellyfinDownloadSourceTest {

    private val itemId = "0000000000000000000000000000000d"
    private val bookId = jellyfinBookId(itemId)
    private val server = ServerConfig("row1", ServerSource.JELLYFIN, "Home", "http://test.invalid:8096", "tok-secret-1")
    private val requests = mutableListOf<HttpRequestData>()
    private val signedOut = mutableListOf<String>()

    private fun source(body: (HttpRequestData) -> Pair<HttpStatusCode, String>): JellyfinDownloadSource {
        val api = JellyfinApiService(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val (status, text) = body(request)
                    respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            ) { installApiDefaults(isDebuggable = false) },
            clientInfo = { id -> JellyfinClient("Android", id, "0.5.4") },
        )
        val install = object : InstallId { override val value = "install-1" }
        return JellyfinDownloadSource(api, install, object : AppInfo { override val versionName = "0.5.4" }, markSignedOut = { signedOut += it.id })
    }

    private fun recorded() = source { HttpStatusCode.OK to fixture("jellyfin/recorded/item-detail.json") }

    /** The recorded item with a media source of the given shape (null leaves the field out). */
    private fun itemWith(container: String? = "m4a", size: Long? = 1_346_649_900L, ticks: Long? = 848_450_416_780L, sources: Boolean = true): String {
        val media = if (!sources) "[]" else buildString {
            append("[{\"Id\":\"s1\"")
            if (container != null) append(",\"Container\":\"$container\"")
            if (size != null) append(",\"Size\":$size")
            append("}]")
        }
        val run = if (ticks != null) ",\"RunTimeTicks\":$ticks" else ""
        return """{"Id":"$itemId","Name":"Test","Type":"AudioBook"$run,"MediaSources":$media,"Chapters":[]}"""
    }

    @Test
    fun `a real item plans as one file with its chapters`() = runTest {
        val plan = recorded().plan(server, bookId)
        assertNull(plan.failure)
        val file = plan.files.single()
        assertEquals(itemId, file.ino)
        assertEquals("$itemId.m4a", file.fileName)
        assertEquals("audio/mp4", file.mimeType)
        assertEquals(1_346_649_900L, file.totalBytes)
        assertTrue(file.durationMs > 80 * 3_600_000L / 4)
        assertEquals(80, plan.chapters.size)
        assertEquals(0L, plan.chapters.first().startMs)
        assertEquals(file.durationMs, plan.chapters.last().endMs)
    }

    @Test
    fun `the plan asks for the item with the header and no token in the address`() = runTest {
        recorded().plan(server, bookId)
        val request = requests.single()
        assertTrue(request.url.encodedPath.endsWith("/Items/$itemId"))
        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("MediaBrowser "))
        assertFalse(request.url.toString().contains("tok-secret-1"))
    }

    @Test
    fun `a book that cannot be downloaded says why`() = runTest {
        assertEquals(JELLYFIN_NO_AUDIO_TEXT, source { HttpStatusCode.OK to itemWith(sources = false) }.plan(server, bookId).failure)
        assertTrue(source { HttpStatusCode.OK to itemWith(container = "wmv") }.plan(server, bookId).failure.orEmpty().contains("format"))
        assertEquals(JELLYFIN_SIZE_UNKNOWN_TEXT, source { HttpStatusCode.OK to itemWith(size = null) }.plan(server, bookId).failure)
        assertEquals(JELLYFIN_SIZE_UNKNOWN_TEXT, source { HttpStatusCode.OK to itemWith(size = 0L) }.plan(server, bookId).failure)
        assertEquals(JELLYFIN_LENGTH_UNKNOWN_TEXT, source { HttpStatusCode.OK to itemWith(ticks = null) }.plan(server, bookId).failure)
        val failed = source { HttpStatusCode.OK to itemWith(size = null) }.plan(server, bookId)
        assertTrue(failed.files.isEmpty())
    }

    @Test
    fun `an ffprobe container list is one mp4 family file`() = runTest {
        val plan = source { HttpStatusCode.OK to itemWith(container = "mov,mp4,m4a,3gp,3g2,mj2") }.plan(server, bookId)
        assertNull(plan.failure)
        assertEquals("$itemId.m4a", plan.files.single().fileName)
    }

    @Test
    fun `the file comes from the Download address with the header and nothing secret in the address`() = runTest {
        var seen: FileFetch? = null
        recorded().authorized(server, bookId, itemId) { seen = it }
        val fetch = seen!!
        assertEquals("http://test.invalid:8096/Items/$itemId/Download", fetch.url)
        val auth = fetch.headers.single { it.first == HttpHeaders.Authorization }.second
        assertTrue(auth.startsWith("MediaBrowser "))
        assertTrue(auth.contains("Token=\"tok-secret-1\""))
        assertFalse(fetch.url.contains("tok-secret-1"))
        assertEquals(ServerSource.JELLYFIN, source { HttpStatusCode.OK to "" }.type)
    }

    @Test
    fun `a file that is not this book's file is gone`() = runTest {
        assertFailsWith<DownloadFileGoneException> { recorded().authorized(server, bookId, "someone-else") { } }
        assertFailsWith<DownloadFileGoneException> { recorded().authorized(server, "abs_li_1", itemId) { } }
    }

    @Test
    fun `a refused token blanks the sign in and says sign in again`() = runTest {
        val s = recorded()
        assertFailsWith<SignInRequiredException> {
            s.authorized(server, bookId, itemId) { throw HttpStatusException(HttpStatusCode.Unauthorized) }
        }
        assertEquals(listOf("row1"), signedOut)
        // another status is not a sign in problem and passes on to the downloader
        val forbidden = assertFailsWith<HttpStatusException> {
            s.authorized(server, bookId, itemId) { throw HttpStatusException(HttpStatusCode.Forbidden) }
        }
        assertEquals(403, forbidden.status.value)
        assertEquals(listOf("row1"), signedOut)
    }

    @Test
    fun `a server with no token is signed out before any request`() = runTest {
        assertFailsWith<SignInRequiredException> { recorded().authorized(server.copy(token = ""), bookId, itemId) { } }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a plan whose item answer is 401 says sign in again`() = runTest {
        val s = source { HttpStatusCode.Unauthorized to "" }
        assertFailsWith<SignInRequiredException> { s.plan(server, bookId) }
        assertEquals(listOf("row1"), signedOut)
    }

    @Test
    fun `a cover is fetched with no credential`() = runTest {
        var seen: FileFetch? = null
        recorded().authorizedSmall(server, "http://test.invalid:8096/Items/$itemId/Images/Primary?tag=t&maxWidth=600") { seen = it }
        assertTrue(seen!!.headers.isEmpty())
    }

    @Test
    fun `the file length is the book length by the file id`() = runTest {
        val lengths = recorded().fileLengths(server, bookId)
        assertEquals(setOf(itemId), lengths.keys)
        assertTrue(lengths.getValue(itemId) > 0L)
        assertTrue(source { HttpStatusCode.OK to itemWith(size = null) }.fileLengths(server, bookId).isEmpty())
    }
}
