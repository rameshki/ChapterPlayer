package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinBookViews
import com.chapterplayer.domain.jellyfin.jellyfinItemToBook
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app's own service and parsers over answers RECORDED from a real Jellyfin 12.4 server (review R39, plan J3 task 1), by
 * `tools/jellyfin-record/record.py`: every value is invented (ids, titles, paths, dates, addresses), the structure and the
 * types are real. Until now the fixtures were written from the API description. The files are in
 * `fixtures/jellyfin/recorded/` (see its README).
 */
class JellyfinRecordedShapesTest {

    private val base = "http://test.invalid:8096"
    private val deviceId = "chapterplayer-test-device"
    private val token = "test-token"

    private fun service(name: String) = JellyfinApiService(
        HttpClient(
            MockEngine { respond(fixture("jellyfin/recorded/$name.json"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        ) { installApiDefaults(isDebuggable = false) },
        clientInfo = { id -> JellyfinClient("Android", id, "0.5.4") },
    )

    @Test
    fun `the public info of a real server parses`() = runTest {
        val info = service("system-info-public").getPublicInfo(base)
        assertEquals("Jellyfin Server", info.productName)
        assertTrue(info.version.orEmpty().startsWith("12."), "version ${info.version}")
        assertTrue(!info.id.isNullOrBlank())
    }

    @Test
    fun `a real sign in answer parses to a token and a user`() = runTest {
        val result = service("authenticate-by-name").authenticateByName(base, deviceId, "user", "password")
        assertTrue(!result.accessToken.isNullOrBlank())
        assertTrue(!result.user?.id.isNullOrBlank())
        assertTrue(!result.serverId.isNullOrBlank())
    }

    @Test
    fun `the real libraries include a books library the app keeps`() = runTest {
        val views = service("user-views").getUserViews(base, deviceId, token)
        assertTrue(views.items.isNotEmpty())
        val libraries = jellyfinBookViews(views.items, "srv")
        assertTrue(libraries.isNotEmpty(), "types: ${views.items.map { it.collectionType }}")
    }

    @Test
    fun `a real page of audiobooks parses and maps to books`() = runTest {
        val page = service("items-audiobooks-page").getAudiobooksPage(base, deviceId, token, "view", 0)
        assertTrue(page.items.isNotEmpty())
        assertNotNull(page.totalRecordCount)
        val books = page.items.mapNotNull { jellyfinItemToBook(it, base, "srv", "lib") }
        assertEquals(page.items.size, books.size, "every audiobook of the page maps")
        for (book in books) {
            assertTrue(book.title.isNotBlank())
            assertTrue(book.durationMs > 0L, "duration of ${book.id}")
        }
    }

    @Test
    fun `a real item detail has the container, the size, the length and the chapters the downloader needs`() = runTest {
        val item = service("item-detail").getItem(base, deviceId, token, "item")
        val source = item.mediaSources.firstOrNull()
        assertNotNull(source, "a media source")
        assertTrue(!source.container.isNullOrBlank(), "container")
        assertTrue((source.size ?: 0L) > 0L, "size")
        assertTrue((source.runTimeTicks ?: item.runTimeTicks ?: 0L) > 0L, "length")
        assertTrue(item.chapters.isNotEmpty(), "chapters")
        assertTrue(item.chapters.all { it.startPositionTicks != null }, "every chapter has a start")
        assertEquals(0L, item.chapters.first().startPositionTicks)
    }

    @Test
    fun `the real user data and configuration parse`() = runTest {
        val data = service("user-data").getUserData(base, deviceId, token, "item")
        assertNotNull(data.playbackPositionTicks)
        assertNotNull(data.played)
        val config = service("system-configuration").getConfiguration(base, deviceId, token)
        assertNotNull(config.minAudiobookResume)
        assertNotNull(config.maxAudiobookResume)
    }
}
