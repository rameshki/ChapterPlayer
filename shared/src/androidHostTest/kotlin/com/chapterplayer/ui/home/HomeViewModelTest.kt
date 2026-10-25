package com.chapterplayer.ui.home

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakePlaybackController
import kotlinx.coroutines.CancellationException
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
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
        val controller = FakePlaybackController()
        val vm = HomeViewModel(
            books, servers, downloads, store, monitor, settings, coordinator,
            PlaybackStarter(controller, books, settings, monitor, FakeLocalBookFiles()), Dispatchers.Main
        )
    }

    private fun seriesBook(id: String, sequence: String) = longBook(id, seriesName = "Saga").copy(seriesSequence = sequence)

    // Long enough that a position of 100 ms is not "at the end".
    private fun longBook(id: String, addedAt: Long = 0L, seriesName: String? = null) =
        com.chapterplayer.testing.testBook(id, addedAt = addedAt, seriesName = seriesName).copy(durationMs = 100_000L)

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
        val s = Setup(FakeBookRepository(listOf(longBook("a"), longBook("b"), longBook("c"))))
        s.books.progress.value = listOf(progress("a", lastPlayedAt = 10), progress("b", lastPlayedAt = 30), progress("c", lastPlayedAt = 20))
        val state = collect(s)

        assertEquals("b", state().resume?.item?.book?.id)
        assertEquals(listOf("c", "a"), state().inProgress.map { it.book.id })
        assertFalse(state().isLoading)
    }

    @Test
    fun `finished and zero position books are not in progress`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"), longBook("b"), longBook("c"))))
        // The repository returns every row, finished and zero-position ones included.
        s.books.progress.value = listOf(progress("a"), progress("b", finished = true), progress("c", positionMs = 0))
        val state = collect(s)

        assertEquals("a", state().resume?.item?.book?.id)
        assertEquals(emptyList(), state().inProgress)
    }

    @Test
    fun `a finished book carries its progress so the finished check shows`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a", addedAt = 1))))
        s.books.progress.value = listOf(progress("a", finished = true))
        val state = collect(s)

        val item = state().recentlyAdded.single()
        assertTrue(coverOverlay(item.progress, item.downloadState, dimmed = false).finished)
        assertEquals(emptyList(), state().inProgress)
    }

    @Test
    fun `the download filter applies to both shelves`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a", addedAt = 1), longBook("b", addedAt = 2))))
        s.books.progress.value = listOf(progress("a"), progress("b", lastPlayedAt = 2))
        s.downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        val state = collect(s)

        s.store.update { it.copy(download = DownloadFilter.DOWNLOADED) }

        // "b" is the newest read and so the card; "a" is the downloaded one left for the shelf.
        assertEquals(listOf("a"), state().inProgress.map { it.book.id })
        assertEquals(listOf("a"), state().recentlyAdded.map { it.book.id })
    }

    @Test
    fun `the Downloaded chip is reported so Home can say nothing is downloaded`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a", addedAt = 1))))
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
        val s = Setup(FakeBookRepository(listOf(longBook("a", addedAt = 1), longBook("b", addedAt = 2))))
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
        val s = Setup(FakeBookRepository(listOf(longBook("a", addedAt = 1), longBook("b", addedAt = 2))))
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
        val dated = (1..21).map { longBook("d$it", addedAt = it * 1000L) }
        val s = Setup(FakeBookRepository(dated + longBook("unknown", addedAt = 0L)))
        val state = collect(s)

        val ids = state().recentlyAdded.map { it.book.id }
        assertEquals(20, ids.size)
        assertEquals("d21", ids.first())
        assertEquals("d2", ids.last())
    }

    @Test
    fun `books with an unknown date come after dated ones when there is room`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("unknown", addedAt = 0), longBook("old", addedAt = 5), longBook("new", addedAt = 9))))
        val state = collect(s)

        assertEquals(listOf("new", "old", "unknown"), state().recentlyAdded.map { it.book.id })
    }

    @Test
    fun `a progress filter that hides books hides them from both shelves`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a", addedAt = 1), longBook("b", addedAt = 2), longBook("c", addedAt = 3))))
        s.books.progress.value = listOf(progress("a"), progress("b", lastPlayedAt = 2))
        val state = collect(s)
        assertEquals(listOf("a"), state().inProgress.map { it.book.id })

        s.store.update { it.copy(progress = ProgressFilter.NOT_STARTED) }

        assertEquals(emptyList(), state().inProgress)
        assertEquals(listOf("c"), state().recentlyAdded.map { it.book.id })
        // The card is not a shelf: the filter leaves it alone.
        assertEquals("b", state().resume?.item?.book?.id)
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
        val s = Setup(FakeBookRepository(listOf(longBook("a"))))
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

    // --- Resume card and Continue Series ---

    @Test
    fun `the resume card is the most recent unfinished started book and leaves Continue Reading`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"), longBook("b"), longBook("c"))))
        s.books.progress.value = listOf(progress("a", lastPlayedAt = 10), progress("b", lastPlayedAt = 30), progress("c", lastPlayedAt = 20))
        val state = collect(s)

        assertEquals("b", state().resume?.item?.book?.id)
        assertEquals(listOf("c", "a"), state().inProgress.map { it.book.id })
    }

    @Test
    fun `no resume card for finished or never started books`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"), longBook("b"), longBook("c"))))
        s.books.progress.value = listOf(progress("a", finished = true), progress("b", positionMs = 0))
        val state = collect(s)

        assertNull(state().resume)
        assertEquals(emptyList(), state().inProgress)
    }

    @Test
    fun `a filter that hides the resume book does not hide the card or the series shelf`() = runTest {
        val books = listOf(
            seriesBook("s1", "1"), seriesBook("s2", "2"), longBook("r")
        )
        val s = Setup(FakeBookRepository(books))
        s.books.progress.value = listOf(progress("s1", finished = true, lastPlayedAt = 5), progress("r", lastPlayedAt = 9))
        val state = collect(s)
        assertEquals("r", state().resume?.item?.book?.id)
        assertEquals(listOf("s2"), state().continueSeries.map { it.book.id })

        s.store.update { queryFor(FilterChip.DOWNLOADED, it) }

        assertEquals(emptyList(), state().recentlyAdded)
        assertEquals("r", state().resume?.item?.book?.id)
        assertEquals(listOf("s2"), state().continueSeries.map { it.book.id })
    }

    @Test
    fun `continue series leaves out the resume book`() = runTest {
        val books = listOf(seriesBook("s1", "1"), seriesBook("s2", "2"))
        val s = Setup(FakeBookRepository(books))
        s.books.progress.value = listOf(progress("s1", finished = true, lastPlayedAt = 5), progress("s2", lastPlayedAt = 9))
        val state = collect(s)

        // s2 is started, so it is the card; the series has nothing else to suggest.
        assertEquals("s2", state().resume?.item?.book?.id)
        assertEquals(emptyList(), state().continueSeries)
    }

    @Test
    fun `offline dims the resume book and the series shelf unless downloaded`() = runTest {
        val books = listOf(
            seriesBook("s1", "1"), seriesBook("s2", "2"), longBook("r")
        )
        val s = Setup(FakeBookRepository(books))
        s.books.progress.value = listOf(progress("s1", finished = true, lastPlayedAt = 5), progress("r", lastPlayedAt = 9))
        val state = collect(s)
        assertFalse(state().dimmed(state().resume!!.item))

        s.monitor.online.value = false
        assertTrue(state().dimmed(state().resume!!.item))
        assertTrue(state().dimmed(state().continueSeries.single()))

        s.downloads.states.value = mapOf("r" to DownloadState.DOWNLOADED)
        assertFalse(state().dimmed(state().resume!!.item))
        assertTrue(state().dimmed(state().continueSeries.single()))
    }

    @Test
    fun `Home without books has no card and no series shelf`() = runTest {
        val state = collect(Setup())

        assertNull(state().resume)
        assertEquals(emptyList(), state().continueSeries)
    }

    @Test
    fun `resume starts the book through the starter without a position and reports success`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"))))
        s.books.progress.value = listOf(ReadingProgress("a", 400, 100_000, 1))
        val state = collect(s)
        var started = 0

        s.vm.resume(state().resume!!.item.book) { started++ }

        assertEquals(listOf("a" to 400L), s.controller.loads)
        assertEquals(1, s.controller.plays)
        assertEquals(1, started)
        assertNull(state().playError)
        assertFalse(state().starting)
    }

    @Test
    fun `a failed start shows a safe message, not the exception text, and dismiss clears it`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"))))
        s.controller.onLoad = { _, _ -> error("secret token=abc at 10.0.0.1") }
        val state = collect(s)
        var started = 0

        s.vm.resume(longBook("a")) { started++ }

        assertEquals(PlaybackStartException.DEFAULT_MESSAGE, state().playError)
        assertEquals(0, started)
        assertFalse(state().starting)

        s.vm.dismissError()
        assertNull(state().playError)
    }

    @Test
    fun `a second resume while one is starting is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = Setup(FakeBookRepository(listOf(longBook("a"))))
        s.controller.onLoad = { _, _ -> gate.await() }
        val state = collect(s)

        s.vm.resume(longBook("a"))
        assertTrue(state().starting)
        s.vm.resume(longBook("a"))
        gate.complete(Unit)

        assertEquals(1, s.controller.loads.size)
        assertFalse(state().starting)
    }

    @Test
    fun `cancelling a start is not shown as an error`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"))))
        s.controller.onLoad = { _, _ -> throw CancellationException("stop") }
        val state = collect(s)

        s.vm.resume(longBook("a"))

        assertNull(state().playError)
        assertFalse(state().starting)
    }

    @Test
    fun `resume on a book that is already loaded plays it without a reload and opens the player`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"))))
        s.books.progress.value = listOf(progress("a", positionMs = 400))
        s.controller.playback.value = com.chapterplayer.data.playback.PlaybackState(bookId = "a", positionMs = 50_000L)
        val state = collect(s)
        var started = 0

        s.vm.resume(state().resume!!.item.book) { started++ }

        assertEquals(emptyList(), s.controller.loads)
        assertEquals(1, s.controller.plays)
        assertEquals(1, started)
    }

    @Test
    fun `the server filter hides other servers' books on the shelves and is reported`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a", addedAt = 1), longBook("b", addedAt = 2).copy(serverId = "s2"))))
        s.servers.flow.value = listOf(testServer("s1"), testServer("s2"))
        val state = collect(s)
        assertFalse(state().serverFilterOn)

        s.store.update { it.copy(serverIds = setOf("s2")) }

        assertEquals(listOf("b"), state().recentlyAdded.map { it.book.id })
        assertTrue(state().serverFilterOn)

        s.vm.onServerFilterCleared()

        assertFalse(state().serverFilterOn)
        assertEquals(listOf("b", "a"), state().recentlyAdded.map { it.book.id })
    }

    @Test
    fun `a server choice left over with one server does not filter Home`() = runTest {
        val s = Setup(FakeBookRepository(listOf(longBook("a"))))
        s.store.update { it.copy(serverIds = setOf("s1")) }
        val state = collect(s)

        assertFalse(state().serverFilterOn)
        assertEquals(listOf("a"), state().recentlyAdded.map { it.book.id })
    }
    @Test
    fun `a book on two servers resumes the copy played last and is not on the shelf twice`() = runTest {
        val a = com.chapterplayer.testing.testBook("a", title = "Same Book").copy(serverId = "abs1", durationMs = 3_600_000L)
        val p = a.copy(id = "p", serverId = "plex1", source = com.chapterplayer.data.model.ServerSource.PLEX)
        val books = FakeBookRepository(listOf(a, p))
        books.progress.value = listOf(
            ReadingProgress("a", 1_000L, 3_600_000L, 100L),
            ReadingProgress("p", 2_000L, 3_600_000L, 500L),
        )
        val servers = FakeServerList(listOf(testServer("abs1"), testServer("plex1")))
        val monitor = FakeMonitor()
        val settings = FakeAppSettings()
        val controller = FakePlaybackController()
        val vm = HomeViewModel(
            books, servers, FakeDownloadStates(), BrowseQueryStore(), monitor, settings,
            LibrarySyncCoordinator(books, servers, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())),
            PlaybackStarter(controller, books, settings, monitor, FakeLocalBookFiles()), Dispatchers.Main,
            merging = com.chapterplayer.data.merge.FixedSourceMerging(
                com.chapterplayer.domain.browse.MergeContext(onlineServerIds = setOf("abs1", "plex1"))
            )
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals("p", vm.uiState.value.resume?.item?.book?.id)
        assertTrue(vm.uiState.value.inProgress.isEmpty())
        vm.resume(vm.uiState.value.resume!!.item.book)
        assertEquals("p", controller.loads.single().first)
    }
}
