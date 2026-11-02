package com.chapterplayer.ui.settings

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.diagnostics.DiagnosticsData
import com.chapterplayer.data.diagnostics.DiagnosticsLogger
import com.chapterplayer.data.diagnostics.folderNameOf
import com.chapterplayer.data.merge.FixedSourceMerging
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.diagnostics.DiagDownload
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Monitor(online: Boolean = true) : ConnectivityMonitor {
        val flow = MutableStateFlow(online)
        override val isOnline: Flow<Boolean> = flow
    }

    private class Data : DiagnosticsData {
        val pendingRows = mutableMapOf<String, List<PendingProgress>>()
        val downloads = mutableMapOf<String, DiagDownload>()
        override suspend fun pending(bookId: String) = pendingRows[bookId].orEmpty()
        override suspend fun download(bookId: String) = downloads[bookId]
    }

    private val logged = mutableListOf<String>()
    private val logger = DiagnosticsLogger { logged += it }

    private val servers = listOf(
        ServerConfig("s1", ServerSource.ABS, "Home ABS", "https://abs.myhome.example.org", "tokentokentokentoken1"),
        ServerConfig("s2", ServerSource.PLEX, "Den Plex", "http://192.168.1.20:32400", "plextokenplextoken12"),
    )
    private val absBook = testBook("li_abc123def456ghi789jk", title = "The Long Way", author = "A Writer")
    private val plexBook = testBook("plex_48213", title = "The Long Way", author = "A Writer")
        .copy(serverId = "s2", source = ServerSource.PLEX)

    private inner class Setup(online: Boolean = true) {
        val repo = FakeBookRepository(listOf(absBook, plexBook))
        val data = Data()
        val monitor = Monitor(online)
        val vm = DiagnosticsViewModel(
            repo, FakeDownloadStates(), FixedSourceMerging(MergeContext(onlineServerIds = setOf("s1", "s2"))),
            FakeServerList(servers), data, logger, monitor, Dispatchers.Main, nowMs = { 1_793_628_185_000L }
        )
    }

    private fun TestScope.collect(s: Setup): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }

    @Test fun `the picker lists the merged book once with its copies`() = runTest {
        val s = Setup(); collect(s); runCurrent()
        val choices = s.vm.uiState.value.choices
        assertEquals(1, choices.size)
        assertEquals("The Long Way", choices[0].title)
        assertEquals("Home ABS (Audiobookshelf), Den Plex (Plex)", choices[0].detail)
        s.vm.onQueryChange("long"); runCurrent()
        assertEquals(1, s.vm.uiState.value.choices.size)
        s.vm.onQueryChange("zzz"); runCurrent()
        assertTrue(s.vm.uiState.value.choices.isEmpty())
    }

    @Test fun `choosing a book shows local pending and download facts without reading a server`() = runTest {
        val s = Setup(); collect(s)
        s.repo.progress.value = listOf(ReadingProgress(absBook.id, 5_000L, 1_000L, 1_793_628_000_000L))
        s.data.pendingRows[absBook.id] = listOf(PendingProgress(absBook.id, "s1", 4_000L, 1_000L, 2_000L, 1_793_628_100_000L, attempts = 3))
        s.data.downloads[absBook.id] = DiagDownload("DONE", 10, 10, listOf("a.mp3"), absBook.id, null)
        runCurrent()
        s.vm.select(absBook.id); runCurrent()
        val report = assertNotNull(s.vm.uiState.value.report)
        assertTrue(report.contains("Book: The Long Way by A Writer"))
        assertTrue(report.contains("== Copy 1: Home ABS (Audiobookshelf) =="))
        assertTrue(report.contains("== Copy 2: Den Plex (Plex) =="))
        assertTrue(report.contains("Server progress: not read yet"))
        assertTrue(report.contains("attempts 3"))
        assertTrue(report.contains("Download: DONE"))
        assertTrue(report.contains("== Merged sources =="))
        assertTrue(s.repo.fetchedProgress.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test fun `reading from the servers fills the report and logs it once`() = runTest {
        val s = Setup(); collect(s); runCurrent()
        s.repo.onFetchServerProgress = { Result.success(ServerProgress(1_000L, 2_000L, false, 1_793_000_000_000L)) }
        s.vm.select(absBook.id); runCurrent()
        s.vm.readFromServer(); runCurrent()
        assertEquals(listOf(absBook.id, plexBook.id), s.repo.fetchedProgress)
        val report = assertNotNull(s.vm.uiState.value.report)
        assertFalse(report.contains("not read yet"))
        assertTrue(report.contains("Server progress (read 2026-11-02 14:03:05 UTC):"))
        assertEquals(1, logged.size)
        assertEquals(report, logged[0])
        assertFalse(s.vm.uiState.value.reading)
        s.vm.logReport()
        assertEquals(2, logged.size)
    }

    @Test fun `a failed read becomes a safe line and secrets never reach the log`() = runTest {
        val s = Setup(); collect(s); runCurrent()
        s.repo.onFetchServerProgress = {
            Result.failure(IllegalStateException("HTTP 500 at https://abs.myhome.example.org/api/me/progress/x?token=tokentokentokentoken1 for 192.168.1.20"))
        }
        s.vm.select(absBook.id); runCurrent()
        s.vm.readFromServer(); runCurrent()
        val report = assertNotNull(s.vm.uiState.value.report)
        assertTrue(report.contains("read failed"))
        for (secret in listOf("tokentokentokentoken1", "myhome.example.org", "192.168.1.20")) {
            assertFalse(report.contains(secret), secret)
            assertFalse(logged.joinToString("\n").contains(secret), secret)
        }
    }

    @Test fun `offline nothing is read or logged and a notice says why`() = runTest {
        val s = Setup(online = false); collect(s); runCurrent()
        s.vm.select(absBook.id); runCurrent()
        s.vm.readFromServer(); runCurrent()
        assertTrue(s.repo.fetchedProgress.isEmpty())
        assertTrue(logged.isEmpty())
        assertTrue(assertNotNull(s.vm.uiState.value.notice).contains("offline"))
        assertFalse(s.vm.uiState.value.online)
    }

    @Test fun `the page changes no data`() = runTest {
        val s = Setup(); collect(s); runCurrent()
        s.vm.select(absBook.id); runCurrent()
        s.vm.readFromServer(); runCurrent()
        s.vm.refreshLocal(); runCurrent()
        assertTrue(s.repo.saved.isEmpty())
        assertTrue(s.repo.queueCalls.isEmpty())
        assertTrue(s.repo.queued.isEmpty())
    }

    @Test fun `clearing the choice goes back to the picker`() = runTest {
        val s = Setup(); collect(s); runCurrent()
        s.vm.select(absBook.id); runCurrent()
        assertNotNull(s.vm.uiState.value.report)
        s.vm.clearSelection(); runCurrent()
        assertNull(s.vm.uiState.value.report)
        assertNull(s.vm.uiState.value.selectedKey)
    }

    @Test fun `the folder name is the books folder only`() {
        assertEquals("li_abc", folderNameOf("/data/user/0/app/files/downloads/s1/li_abc/01.mp3"))
        assertEquals("li_abc", folderNameOf("C:\\x\\downloads\\s1\\li_abc\\01.mp3"))
        assertNull(folderNameOf("01.mp3"))
    }
}
