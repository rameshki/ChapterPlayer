package com.chapterplayer.ui.home

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.DownloadFilter
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.queryFor
import com.chapterplayer.domain.browse.coverOverlay
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import com.chapterplayer.testing.testServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeMonitor : ConnectivityMonitor {
        val online = MutableStateFlow(true)
        override val isOnline: Flow<Boolean> = online
    }

    private class Setup(val books: FakeBookRepository = FakeBookRepository()) {
        val servers = FakeServerList(listOf(testServer()))
        val coordinator = LibrarySyncCoordinator(books, servers, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()))
        val downloads = FakeDownloadStates()
        val store = BrowseQueryStore()
        val monitor = FakeMonitor()
        val settings = FakeAppSettings()
        val vm = HomeViewModel(books, servers, downloads, store, monitor, settings, coordinator, Dispatchers.Main)
    }

    private fun progress(id: String, positionMs: Long = 100, lastPlayedAt: Long = 1, finished: Boolean = false) =
        ReadingProgress(id, positionMs, 1_000, lastPlayedAt, finished)

    private fun TestScope.collect(setup: Setup): () -> HomeUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { setup.vm.uiState.collect {} }
        return { setup.vm.uiState.value }
    }

    @Test
    fun `the state is loading until the data arrives`() {
        assertTrue(Setup().vm.uiState.value.isLoading)
    }

    @Test
    fun `in progress books are ordered by last played, newest first`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a"), testBook("b"), testBook("c"))))
        s.books.progress.value = listOf(progress("a", lastPlayedAt = 10), progress("b", lastPlayedAt = 30), progress("c", lastPlayedAt = 20))
        val state = collect(s)

        assertEquals(listOf("b", "c", "a"), state().inProgress.map { it.book.id })
        assertFalse(state().isLoading)
    }

    @Test
    fun `finished and zero position books are not in progress`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a"), testBook("b"), testBook("c"))))
        // The repository returns every row, finished and zero-position ones included.
        s.books.progress.value = listOf(progress("a"), progress("b", finished = true), progress("c", positionMs = 0))
        val state = collect(s)

        assertEquals(listOf("a"), state().inProgress.map { it.book.id })
    }

    @Test
    fun `a finished book carries its progress so the finished check shows`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a", addedAt = 1))))
        s.books.progress.value = listOf(progress("a", finished = true))
        val state = collect(s)

        val item = state().recentlyAdded.single()
        assertTrue(coverOverlay(item.progress, item.downloadState, dimmed = false).finished)
        assertEquals(emptyList(), state().inProgress)
    }

    @Test
    fun `the download filter applies to both shelves`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a", addedAt = 1), testBook("b", addedAt = 2))))
        s.books.progress.value = listOf(progress("a"), progress("b"))
        s.downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        val state = collect(s)

        s.store.update { it.copy(download = DownloadFilter.DOWNLOADED) }

        assertEquals(listOf("a"), state().inProgress.map { it.book.id })
        assertEquals(listOf("a"), state().recentlyAdded.map { it.book.id })
    }

    @Test
    fun `the Downloaded chip is reported so Home can say nothing is downloaded`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a", addedAt = 1))))
        val state = collect(s)
        assertFalse(state().downloadedFilterOn)

        s.store.update { queryFor(FilterChip.DOWNLOADED, it) }

        assertTrue(state().downloadedFilterOn)
        assertTrue(state().hasBooks)
        assertEquals(emptyList(), state().recentlyAdded)

        s.store.update { queryFor(FilterChip.FINISHED, it) }
        assertFalse(state().downloadedFilterOn)
    }

    @Test
    fun `offline dims a book that is not downloaded but never a downloaded one`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a", addedAt = 1), testBook("b", addedAt = 2))))
        s.downloads.states.value = mapOf("b" to DownloadState.DOWNLOADED)
        val state = collect(s)
        val byId = { id: String -> state().recentlyAdded.first { it.book.id == id } }
        assertFalse(state().dimmed(byId("a")))

        s.monitor.online.value = false

        assertTrue(state().dimmed(byId("a")))
        assertFalse(state().dimmed(byId("b")))
    }

    @Test
    fun `downloaded only dims a book that is not downloaded even while online`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a", addedAt = 1), testBook("b", addedAt = 2))))
        s.downloads.states.value = mapOf("b" to DownloadState.DOWNLOADED)
        val state = collect(s)

        s.settings.downloadedOnlyFlow.value = true

        assertTrue(state().online)
        assertTrue(state().dimmed(state().recentlyAdded.first { it.book.id == "a" }))
        assertFalse(state().dimmed(state().recentlyAdded.first { it.book.id == "b" }))
    }

    @Test
    fun `isSyncing follows the library sync`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = object : FakeBookRepository() {
            override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> {
                gate.await()
                return Result.success(Unit)
            }
        }
        val s = Setup(repo)
        val state = collect(s)
        assertFalse(state().isSyncing)

        s.coordinator.start()
        assertTrue(state().isSyncing)

        gate.complete(Unit)
        assertFalse(state().isSyncing)
    }

    @Test
    fun `recently added holds the newest 20 with unknown dates last`() = runTest {
        val dated = (1..21).map { testBook("d$it", addedAt = it * 1000L) }
        val s = Setup(FakeBookRepository(dated + testBook("unknown", addedAt = 0L)))
        val state = collect(s)

        val ids = state().recentlyAdded.map { it.book.id }
        assertEquals(20, ids.size)
        assertEquals("d21", ids.first())
        assertEquals("d2", ids.last())
    }

    @Test
    fun `books with an unknown date come after dated ones when there is room`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("unknown", addedAt = 0), testBook("old", addedAt = 5), testBook("new", addedAt = 9))))
        val state = collect(s)

        assertEquals(listOf("new", "old", "unknown"), state().recentlyAdded.map { it.book.id })
    }

    @Test
    fun `a progress filter that hides books hides them from both shelves`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a", addedAt = 1), testBook("b", addedAt = 2))))
        s.books.progress.value = listOf(progress("a"))
        val state = collect(s)
        assertEquals(listOf("a"), state().inProgress.map { it.book.id })

        s.store.update { it.copy(progress = ProgressFilter.NOT_STARTED) }

        assertEquals(emptyList(), state().inProgress)
        assertEquals(listOf("b"), state().recentlyAdded.map { it.book.id })
        assertTrue(state().hasBooks)
    }

    @Test
    fun `hasServers follows the configured servers`() = runTest {
        val s = Setup()
        val state = collect(s)
        assertTrue(state().hasServers)

        s.servers.flow.value = emptyList()

        assertFalse(state().hasServers)
    }

    @Test
    fun `download states, connectivity and the downloaded only setting flow through`() = runTest {
        val s = Setup(FakeBookRepository(listOf(testBook("a"))))
        val state = collect(s)
        assertTrue(state().online)
        assertFalse(state().downloadedOnly)

        s.downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        s.monitor.online.value = false
        s.settings.downloadedOnlyFlow.value = true

        assertEquals(DownloadState.DOWNLOADED, state().recentlyAdded.single().downloadState)
        assertFalse(state().online)
        assertTrue(state().downloadedOnly)
    }
}
