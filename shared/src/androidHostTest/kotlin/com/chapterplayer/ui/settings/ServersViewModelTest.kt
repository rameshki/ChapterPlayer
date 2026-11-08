package com.chapterplayer.ui.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installProbeDefaults
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeServerList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val jf = ServerConfig("j1", ServerSource.JELLYFIN, "Den Jellyfin", "https://jf.test/jellyfin", "secret-jf")
    private val requests = mutableListOf<HttpRequestData>()

    private fun probe(answer: () -> Triple<String, HttpStatusCode, String>): JellyfinApiService =
        JellyfinApiService(HttpClient(MockEngine { request ->
            requests += request
            val (body, status, type) = answer()
            respond(body, status, headersOf(HttpHeaders.ContentType, type))
        }) { installProbeDefaults(isDebuggable = false) }) { JellyfinClient("Android", it, "0.4.1") }

    private fun viewModel(api: JellyfinApiService): ServersViewModel {
        val servers = FakeServerList(listOf(jf))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return ServersViewModel(servers, LibrarySyncCoordinator(FakeBookRepository(), servers, scope), scope, jellyfinProbe = api)
    }

    /** The finished dialog: the probe answers on the engine's own dispatcher, so the test waits for it. */
    private fun ServersViewModel.finishedTest(): ConnectionTestUi = runBlocking {
        withTimeout(5_000) { connectionTest.first { it != null && !it.running }!! }
    }

    @Test
    fun `a Jellyfin row can test its connection`() {
        val rows = serverRows(listOf(jf), emptyMap(), nowMs = 0L)
        assertTrue(rows.single().canTestConnection)
        assertEquals("Jellyfin", rows.single().typeLabel)
    }

    @Test
    fun `a Jellyfin server's connection is its address`() {
        val status = com.chapterplayer.data.sync.serverSyncStatus(jf, com.chapterplayer.data.sync.ServerStatusRecord(), 0, false)
        assertEquals("Jellyfin address", status.connectionLabel)
    }

    @Test
    fun `test connection reads the public info only and names the version`() {
        val vm = viewModel(probe { Triple(fixture("jellyfin/system-info-public.json"), HttpStatusCode.OK, "application/json") })

        vm.testConnection("j1")

        val ui = vm.finishedTest()
        val row = ui.rows.single()
        assertTrue(row.ok)
        assertEquals("Jellyfin 12.4 answered", row.result)
        assertEquals("https://jf.test/jellyfin", row.address)
        val request = requests.single()
        assertEquals("/jellyfin/System/Info/Public", request.url.encodedPath)
        assertNull(request.headers[HttpHeaders.Authorization])
        assertFalse(request.url.toString().contains("secret-jf"))
    }

    @Test
    fun `test connection says why it failed`() {
        val html = viewModel(probe { Triple("<html></html>", HttpStatusCode.OK, "text/html") })
        html.testConnection("j1")
        assertEquals("This is not a Jellyfin server", html.finishedTest().rows.single().result)
        assertFalse(html.finishedTest().rows.single().ok)

        val old = viewModel(probe { Triple(fixture("jellyfin/system-info-public-10-9.json"), HttpStatusCode.OK, "application/json") })
        old.testConnection("j1")
        assertEquals("This Jellyfin version is too old (10.10 or newer is needed)", old.finishedTest().rows.single().result)

        val down = viewModel(probe { throw IOException("connect failed") })
        down.testConnection("j1")
        val downRow = down.finishedTest().rows.single()
        assertFalse(downRow.ok)
        assertTrue(downRow.result.isNotBlank())
        assertFalse(downRow.result.contains("secret-jf"))

        val moved = viewModel(probe { Triple("", HttpStatusCode.MovedPermanently, "text/html") })
        moved.testConnection("j1")
        assertEquals(com.chapterplayer.domain.jellyfin.JELLYFIN_REDIRECT_TEXT, moved.finishedTest().rows.single().result)

        val error = viewModel(probe { Triple("", HttpStatusCode.BadGateway, "text/plain") })
        error.testConnection("j1")
        assertEquals("The server answered with HTTP 502", error.finishedTest().rows.single().result)
    }
}
