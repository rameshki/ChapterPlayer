package com.chapterplayer.ui.library

import androidx.lifecycle.SavedStateHandle
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.BrowseSort
import com.chapterplayer.domain.browse.DownloadFilter
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.domain.browse.ServerFilterOption
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import com.chapterplayer.testing.testServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.chapterplayer.data.model.ServerConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeMonitor : ConnectivityMonitor {
        val online = MutableStateFlow(true)
        override val isOnline: Flow<Boolean> = online
    }

    private class Setup(
        val books: FakeBookRepository = FakeBookRepository(),
        servers: List<ServerConfig> = listOf(testServer())
    ) {
        val servers = FakeServerList(servers)
        val downloads = FakeDownloadStates()
        val store = BrowseQueryStore()
        val monitor = FakeMonitor()
        val settings = FakeAppSettings()
        val coordinator = LibrarySyncCoordinator(books, this.servers, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()))
        val vm = LibraryViewModel(books, this.servers, downloads, store, monitor, settings, coordinator, Dispatchers.Main)
    }

    private fun progress(id: String, positionMs: Long = 100, finished: Boolean = false, lastPlayedAt: Long = 1) =
        ReadingProgress(id, positionMs, 1_000, lastPlayedAt, finished)

    private fun TestScope.collect(setup: Setup): () -> LibraryUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { setup.vm.uiState.collect {} }
        return { setup.vm.uiState.value }
    }

    private fun library(vararg books: AudioBook) = Setup(FakeBookRepository(books.toList()))

    @Test
    fun `the state is loading until the data arrives`() {
        assertTrue(library().vm.uiState.value.isLoading)
    }

    @Test
    fun `the Library view lists the books in the chosen sort`() = runTest {
        val s = library(testBook("a", title = "B", addedAt = 2), testBook("b", title = "A", addedAt = 1))
        val state = collect(s)
        assertEquals(listOf("a", "b"), state().items.map { it.book.id })

        s.vm.onSortSelected(BrowseSort.TITLE)

        assertEquals(listOf("b", "a"), state().items.map { it.book.id })
        assertEquals(BrowseSort.TITLE, state().query.sort)
    }

    @Test
    fun `a new install starts on the Library view`() = runTest {
        val state = collect(library(testBook("a")))
        assertEquals(LibraryView.LIBRARY, state().view)
    }

    @Test
    fun `the Authors view groups by author and the Library items are left empty`() = runTest {
        val s = library(
            testBook("a", title = "One", author = "Zed"),
            testBook("b", title = "Two", author = "Amy"),
            testBook("c", title = "Three", author = "Amy")
        )
        val state = collect(s)

        s.vm.onViewSelected(LibraryView.AUTHORS)

        assertEquals(LibraryView.AUTHORS, state().view)
        assertEquals(listOf("Amy", "Zed"), state().groups.map { it.title })
        assertEquals(listOf(2, 1), state().groups.map { it.items.size })
        assertTrue(state().items.isEmpty())
    }

    @Test
    fun `the Narrators view groups by narrator`() = runTest {
        val s = library(
            testBook("a", narrator = "Nia"),
            testBook("b", narrator = "Nia"),
            testBook("c", narrator = "Max")
        )
        val state = collect(s)

        s.vm.onViewSelected(LibraryView.NARRATORS)

        assertEquals(listOf("Max" to 1, "Nia" to 2), state().groups.map { it.title to it.items.size })
    }

    @Test
    fun `the Series view groups by series name and skips books without one`() = runTest {
        val s = library(
            testBook("a", seriesName = "Saga"),
            testBook("b", seriesName = "Saga"),
            testBook("c")
        )
        val state = collect(s)

        s.vm.onViewSelected(LibraryView.SERIES)

        assertEquals(listOf("Saga" to 2), state().groups.map { it.title to it.items.size })
    }

    @Test
    fun `the Collections view lists the repository's collections with the cached books`() = runTest {
        val s = library(testBook("a"), testBook("b"), testBook("c"))
        s.books.collections.value = listOf(
            BookCollection("s1", "c1", "Favourites", listOf("b", "a", "gone")),
            BookCollection("s1", "c2", "Empty", listOf("gone"))
        )
        val state = collect(s)

        s.vm.onViewSelected(LibraryView.COLLECTIONS)

        assertEquals(listOf("Favourites"), state().groups.map { it.title })
        assertEquals(listOf("b", "a"), state().groups.single().items.map { it.book.id })
    }

    @Test
    fun `the chosen view is kept in the saved state and comes back with a new view model`() = runTest {
        val handle = SavedStateHandle()
        val first = library(testBook("a"))
        val firstVm = LibraryViewModel(
            first.books, first.servers, first.downloads, first.store, first.monitor, first.settings, first.coordinator, Dispatchers.Main, handle
        )
        firstVm.onViewSelected(LibraryView.NARRATORS)

        val second = library(testBook("a"))
        val secondVm = LibraryViewModel(
            second.books, second.servers, second.downloads, second.store, second.monitor, second.settings, second.coordinator, Dispatchers.Main, handle
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { secondVm.uiState.collect {} }

        assertEquals(LibraryView.NARRATORS, secondVm.uiState.value.view)
    }

    @Test
    fun `a damaged saved view falls back to the Library view`() = runTest {
        val handle = SavedStateHandle(mapOf("library_view" to "NOT_A_VIEW"))
        val s = library(testBook("a"))
        val vm = LibraryViewModel(s.books, s.servers, s.downloads, s.store, s.monitor, s.settings, s.coordinator, Dispatchers.Main, handle)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(LibraryView.LIBRARY, vm.uiState.value.view)
    }

    @Test
    fun `libraries that are all turned off are reported, with no books cached`() = runTest {
        val s = Setup(servers = listOf(testServer().copy(excludedLibraryIds = setOf("l1", "l2"))))
        s.books.libraries.value = listOf(LibraryInfo("s1", "l1", "One"), LibraryInfo("s1", "l2", "Two"))
        val state = collect(s)

        assertTrue(state().allLibrariesHidden)
        assertFalse(state().hasBooks)
    }

    @Test
    fun `one library still on means not all are hidden`() = runTest {
        val s = Setup(servers = listOf(testServer().copy(excludedLibraryIds = setOf("l1"))))
        s.books.libraries.value = listOf(LibraryInfo("s1", "l1", "One"), LibraryInfo("s1", "l2", "Two"))
        val state = collect(s)

        assertFalse(state().allLibrariesHidden)
    }

    @Test
    fun `a server whose libraries are not synced yet is not reported as hidden`() = runTest {
        val state = collect(Setup(servers = listOf(testServer().copy(excludedLibraryIds = setOf("l1")))))

        assertFalse(state().allLibrariesHidden)
    }

    @Test
    fun `the selected view survives while the screen is collected again`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)
        s.vm.onViewSelected(LibraryView.SERIES)

        collect(s)

        assertEquals(LibraryView.SERIES, state().view)
    }

    @Test
    fun `the layout comes from the settings and defaults to the large grid`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)
        assertEquals(LibraryLayout.LARGE_GRID, state().layout)

        s.settings.layout.value = LibraryLayout.LIST

        assertEquals(LibraryLayout.LIST, state().layout)
    }

    @Test
    fun `choosing a layout writes it to the settings`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)

        s.vm.onLayoutSelected(LibraryLayout.SMALL_GRID)

        assertEquals(LibraryLayout.SMALL_GRID, s.settings.layout.value)
        assertEquals(LibraryLayout.SMALL_GRID, state().layout)
    }

    @Test
    fun `each chip writes the query and keeps the sort`() = runTest {
        val s = library(testBook("a"))
        collect(s)
        s.vm.onSortSelected(BrowseSort.TITLE)

        s.vm.onChipSelected(FilterChip.DOWNLOADED)
        assertEquals(BrowseQuery(ProgressFilter.ALL, DownloadFilter.DOWNLOADED, BrowseSort.TITLE), s.store.query.value)

        s.vm.onChipSelected(FilterChip.IN_PROGRESS)
        assertEquals(BrowseQuery(ProgressFilter.IN_PROGRESS, DownloadFilter.ALL, BrowseSort.TITLE), s.store.query.value)

        s.vm.onChipSelected(FilterChip.NOT_STARTED)
        assertEquals(ProgressFilter.NOT_STARTED, s.store.query.value.progress)

        s.vm.onChipSelected(FilterChip.FINISHED)
        assertEquals(ProgressFilter.FINISHED, s.store.query.value.progress)

        s.vm.onChipSelected(FilterChip.ALL)
        assertEquals(BrowseQuery(sort = BrowseSort.TITLE), s.store.query.value)
    }

    @Test
    fun `the selected chip follows the query`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)
        assertEquals(FilterChip.ALL, state().chip)

        s.vm.onChipSelected(FilterChip.FINISHED)
        assertEquals(FilterChip.FINISHED, state().chip)

        // Another screen changing the shared query is seen too.
        s.store.update { it.copy(progress = ProgressFilter.ALL, download = DownloadFilter.DOWNLOADED) }
        assertEquals(FilterChip.DOWNLOADED, state().chip)
    }

    @Test
    fun `a combination from the sheet highlights no chip`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)

        s.vm.onProgressFilterSelected(ProgressFilter.IN_PROGRESS)
        s.vm.onDownloadFilterSelected(DownloadFilter.DOWNLOADED)

        assertNull(state().chip)
        assertEquals(ProgressFilter.IN_PROGRESS, s.store.query.value.progress)
        assertEquals(DownloadFilter.DOWNLOADED, s.store.query.value.download)
    }

    @Test
    fun `the not downloaded option reaches the store`() = runTest {
        val s = library(testBook("a"))
        collect(s)

        s.vm.onDownloadFilterSelected(DownloadFilter.NOT_DOWNLOADED)

        assertEquals(DownloadFilter.NOT_DOWNLOADED, s.store.query.value.download)
    }

    @Test
    fun `clearing the filters resets the query`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)
        s.vm.onChipSelected(FilterChip.FINISHED)
        s.vm.onSortSelected(BrowseSort.LENGTH)

        s.vm.onClearFilters()

        assertEquals(BrowseQuery(), s.store.query.value)
        assertEquals(FilterChip.ALL, state().chip)
    }

    @Test
    fun `finished books are found by the Finished chip and left out of the Not started chip`() = runTest {
        val s = library(testBook("a"), testBook("b"), testBook("c"), testBook("d"))
        s.books.progress.value = listOf(progress("a", finished = true), progress("b"), progress("c", positionMs = 0))
        val state = collect(s)

        s.vm.onChipSelected(FilterChip.FINISHED)
        assertEquals(listOf("a"), state().items.map { it.book.id })

        s.vm.onChipSelected(FilterChip.IN_PROGRESS)
        assertEquals(listOf("b"), state().items.map { it.book.id })

        s.vm.onChipSelected(FilterChip.NOT_STARTED)
        assertEquals(setOf("c", "d"), state().items.map { it.book.id }.toSet())
    }

    @Test
    fun `the Downloaded chip lists only the books on the phone`() = runTest {
        val s = library(testBook("a"), testBook("b"))
        s.downloads.states.value = mapOf("b" to DownloadState.DOWNLOADED)
        val state = collect(s)

        s.vm.onChipSelected(FilterChip.DOWNLOADED)

        assertEquals(listOf("b"), state().items.map { it.book.id })
    }

    @Test
    fun `a filter that hides everything says so and the unfiltered state does not`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)
        assertFalse(state().emptyBecauseOfFilter)

        s.vm.onChipSelected(FilterChip.FINISHED)

        assertTrue(state().items.isEmpty())
        assertTrue(state().emptyBecauseOfFilter)
        assertFalse(state().nothingDownloaded)
        assertTrue(state().hasBooks)
    }

    @Test
    fun `the Downloaded chip with nothing downloaded sets the nothing downloaded flag`() = runTest {
        val s = library(testBook("a"))
        val state = collect(s)

        s.vm.onChipSelected(FilterChip.DOWNLOADED)

        assertTrue(state().nothingDownloaded)
        assertTrue(state().emptyBecauseOfFilter)
    }

    @Test
    fun `an empty cache is not an empty-because-of-filter state`() = runTest {
        val s = library()
        val state = collect(s)

        s.vm.onChipSelected(FilterChip.DOWNLOADED)

        assertFalse(state().hasBooks)
        assertFalse(state().emptyBecauseOfFilter)
        assertFalse(state().nothingDownloaded)
    }

    @Test
    fun `a group whose books are all filtered out disappears and comes back when the filter clears`() = runTest {
        val s = library(testBook("a", author = "Amy"), testBook("b", author = "Zed"))
        s.books.progress.value = listOf(progress("a", finished = true))
        val state = collect(s)
        s.vm.onViewSelected(LibraryView.AUTHORS)
        assertEquals(listOf("Amy", "Zed"), state().groups.map { it.title })

        s.vm.onChipSelected(FilterChip.FINISHED)

        assertEquals(listOf("Amy"), state().groups.map { it.title })

        s.vm.onClearFilters()
        assertEquals(listOf("Amy", "Zed"), state().groups.map { it.title })
    }

    @Test
    fun `group counts only count the books that pass the filter`() = runTest {
        val s = library(testBook("a", author = "Amy"), testBook("b", author = "Amy"), testBook("c", author = "Amy"))
        s.downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED, "b" to DownloadState.DOWNLOADED)
        val state = collect(s)
        s.vm.onViewSelected(LibraryView.AUTHORS)
        assertEquals(3, state().groups.single().items.size)

        s.vm.onChipSelected(FilterChip.DOWNLOADED)

        assertEquals(2, state().groups.single().items.size)
    }

    @Test
    fun `a grouped view with every book filtered out is empty because of the filter`() = runTest {
        val s = library(testBook("a", author = "Amy"))
        val state = collect(s)
        s.vm.onViewSelected(LibraryView.AUTHORS)

        s.vm.onChipSelected(FilterChip.FINISHED)

        assertTrue(state().groups.isEmpty())
        assertTrue(state().emptyBecauseOfFilter)
    }

    @Test
    fun `servers and syncing are reported`() = runTest {
        val none = Setup(servers = emptyList())
        val noneState = collect(none)
        assertFalse(noneState().hasServers)

        val gate = CompletableDeferred<Unit>()
        val books = object : FakeBookRepository() {
            override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> {
                gate.await()
                return Result.success(Unit)
            }
        }
        val s = Setup(books)
        val state = collect(s)
        s.coordinator.start()

        assertTrue(state().hasServers)
        assertFalse(state().hasBooks)
        assertTrue(state().isSyncing)
        gate.complete(Unit)
        assertFalse(state().isSyncing)
    }

    @Test
    fun `sign in problems and sync errors come through from the coordinator`() = runTest {
        val s = Setup(servers = listOf(testServer("a").copy(token = ""), testServer("b").copy(token = "")))
        val state = collect(s)
        s.coordinator.start()

        assertTrue(state().allServersNeedSignIn)
        assertEquals(listOf("Server a", "Server b"), state().signInRequiredNames)
        assertEquals("Server a: Sign in again in Settings\nServer b: Sign in again in Settings", state().syncError)

        s.vm.onErrorDismissed()
        assertNull(state().syncError)
    }

    @Test
    fun `offline and downloaded only dim the books that are not on the phone`() = runTest {
        val s = library(testBook("a"), testBook("b"))
        s.downloads.states.value = mapOf("b" to DownloadState.DOWNLOADED)
        val state = collect(s)
        val item = { id: String -> state().items.first { it.book.id == id } }
        assertFalse(state().dimmed(item("a")))

        s.monitor.online.value = false
        assertTrue(state().dimmed(item("a")))
        assertFalse(state().dimmed(item("b")))

        s.monitor.online.value = true
        s.settings.downloadedOnlyFlow.value = true
        assertTrue(state().dimmed(item("a")))
        assertFalse(state().dimmed(item("b")))
    }

    // ---- server filter ----

    private fun twoServers(vararg books: AudioBook) = Setup(
        FakeBookRepository(books.toList()),
        servers = listOf(testServer("s1"), testServer("s2").copy(name = "Plex box", source = ServerSource.PLEX))
    )

    @Test
    fun `with one server there is no server section and no server chip`() = runTest {
        val state = collect(library(testBook("a")))

        assertEquals(emptyList(), state().serverOptions)
        assertNull(state().serverChip)
    }

    @Test
    fun `the server options count every book of each server whatever the filters`() = runTest {
        val s = twoServers(testBook("a"), testBook("b"), testBook("c").copy(serverId = "s2"))
        val state = collect(s)
        s.vm.onChipSelected(FilterChip.FINISHED)

        assertEquals(
            listOf(
                ServerFilterOption("s1", "Server s1", "ABS", 2, selected = true),
                ServerFilterOption("s2", "Plex box", "Plex", 1, selected = true)
            ),
            state().serverOptions
        )
    }

    @Test
    fun `unticking a server hides its books and shows the chip`() = runTest {
        val s = twoServers(testBook("a"), testBook("c").copy(serverId = "s2"))
        val state = collect(s)

        s.vm.onServerToggled("s1")

        assertEquals(listOf("c"), state().items.map { it.book.id })
        assertEquals(setOf("s2"), s.store.query.value.serverIds)
        assertEquals("Plex box", state().serverChip)
        assertEquals(listOf(false, true), state().serverOptions.map { it.selected })
    }

    @Test
    fun `the server filter reaches the grouped views`() = runTest {
        val s = twoServers(testBook("a", author = "Amy"), testBook("c", author = "Zed").copy(serverId = "s2"))
        val state = collect(s)
        s.vm.onViewSelected(LibraryView.AUTHORS)

        s.vm.onServerToggled("s2")

        assertEquals(listOf("Amy"), state().groups.map { it.title })
    }

    @Test
    fun `clearing the server chip shows every server again and keeps the other filters`() = runTest {
        val s = twoServers(testBook("a"), testBook("c").copy(serverId = "s2"))
        val state = collect(s)
        s.vm.onChipSelected(FilterChip.DOWNLOADED)
        s.vm.onServerToggled("s1")

        s.vm.onServerFilterCleared()

        assertEquals(emptySet(), s.store.query.value.serverIds)
        assertEquals(DownloadFilter.DOWNLOADED, s.store.query.value.download)
        assertNull(state().serverChip)
    }

    @Test
    fun `a server filter that hides everything is empty because of the filter`() = runTest {
        val s = twoServers(testBook("a"))
        val state = collect(s)

        s.vm.onServerToggled("s1")

        assertTrue(state().items.isEmpty())
        assertTrue(state().emptyBecauseOfFilter)
    }
}
