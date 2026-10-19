package com.chapterplayer.ui.library

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.DownloadFilter
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeMonitor : ConnectivityMonitor {
        val online = MutableStateFlow(true)
        override val isOnline: Flow<Boolean> = online
    }

    private class Setup(kind: LibraryView, key: String, books: List<AudioBook>) {
        val repo = FakeBookRepository(books)
        val downloads = FakeDownloadStates()
        val store = BrowseQueryStore()
        val monitor = FakeMonitor()
        val settings = FakeAppSettings()
        val vm = GroupDetailViewModel(kind, key, repo, downloads, store, monitor, settings, Dispatchers.Main)
    }

    private fun TestScope.collect(setup: Setup): () -> GroupDetailUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { setup.vm.uiState.collect {} }
        return { setup.vm.uiState.value }
    }

    @Test
    fun `an author's detail lists that author's books`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy"), testBook("b", author = "Zed"), testBook("c", author = "Amy")))
        val state = collect(s)

        assertEquals("Amy", state().title)
        assertTrue(state().exists)
        assertEquals(setOf("a", "c"), state().items.map { it.book.id }.toSet())
        assertFalse(state().isLoading)
    }

    @Test
    fun `a series detail lists the series in reading order`() = runTest {
        val s = Setup(
            LibraryView.SERIES, "saga",
            listOf(
                testBook("b", seriesName = "Saga").copy(seriesSequence = "2"),
                testBook("a", seriesName = "Saga").copy(seriesSequence = "1")
            )
        )
        val state = collect(s)

        assertEquals(listOf("a", "b"), state().items.map { it.book.id })
    }

    @Test
    fun `a collection detail uses the collection key and its order`() = runTest {
        val s = Setup(LibraryView.COLLECTIONS, "s1/c1", listOf(testBook("a"), testBook("b")))
        s.repo.collections.value = listOf(BookCollection("s1", "c1", "Favourites", listOf("b", "a")))
        val state = collect(s)

        assertEquals("Favourites", state().title)
        assertEquals(listOf("b", "a"), state().items.map { it.book.id })
    }

    @Test
    fun `the books come from the current query`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy"), testBook("b", author = "Amy")))
        s.downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        val state = collect(s)

        s.store.update { it.copy(download = DownloadFilter.DOWNLOADED) }

        assertEquals(listOf("a"), state().items.map { it.book.id })
    }

    @Test
    fun `the Downloaded filter with nothing downloaded says so instead of the generic filter text`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy")))
        val state = collect(s)

        s.store.update { it.copy(download = DownloadFilter.DOWNLOADED) }

        assertTrue(state().nothingDownloaded)
        assertTrue(state().emptyBecauseOfFilter)
    }

    @Test
    fun `another filter hiding the group is not reported as nothing downloaded`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy")))
        val state = collect(s)

        s.store.update { it.copy(progress = ProgressFilter.FINISHED) }

        assertFalse(state().nothingDownloaded)
    }

    @Test
    fun `a filter change keeps the group title and a book change updates it`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy")))
        val state = collect(s)
        s.store.update { it.copy(progress = ProgressFilter.FINISHED) }
        assertEquals("Amy", state().title)

        s.repo.books.value = listOf(testBook("a", author = "Amy"), testBook("b", author = "Amy"))
        s.store.update { BrowseQuery() }

        assertEquals(listOf("a", "b"), state().items.map { it.book.id }.sorted())
    }

    @Test
    fun `finished books are found by the finished filter`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy"), testBook("b", author = "Amy")))
        s.repo.progress.value = listOf(ReadingProgress("a", 1_000, 1_000, 1, true))
        val state = collect(s)

        s.store.update { it.copy(progress = ProgressFilter.FINISHED) }

        assertEquals(listOf("a"), state().items.map { it.book.id })
    }

    @Test
    fun `a filter that hides the whole group keeps the title and says it is the filter`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy")))
        val state = collect(s)

        s.store.update { it.copy(progress = ProgressFilter.FINISHED) }

        assertEquals("Amy", state().title)
        assertTrue(state().exists)
        assertTrue(state().items.isEmpty())
        assertTrue(state().emptyBecauseOfFilter)

        s.vm.onClearFilters()
        assertEquals(BrowseQuery(), s.store.query.value)
        assertEquals(listOf("a"), state().items.map { it.book.id })
        assertFalse(state().emptyBecauseOfFilter)
    }

    @Test
    fun `a key that no longer exists gives a not found state without crashing`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "gone", listOf(testBook("a", author = "Amy")))
        val state = collect(s)

        assertFalse(state().exists)
        assertNull(state().title)
        assertTrue(state().items.isEmpty())
        assertFalse(state().emptyBecauseOfFilter)
        assertFalse(state().isLoading)
    }

    @Test
    fun `a group that empties while open becomes not found`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy")))
        val state = collect(s)
        assertTrue(state().exists)

        s.repo.books.value = emptyList()

        assertFalse(state().exists)
    }

    @Test
    fun `the layout and offline dimming follow the settings and the connection`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy")))
        val state = collect(s)
        val item = { state().items.single() }
        assertFalse(state().dimmed(item()))

        s.monitor.online.value = false
        s.settings.layout.value = com.chapterplayer.data.settings.LibraryLayout.LIST

        assertTrue(state().dimmed(item()))
        assertEquals(com.chapterplayer.data.settings.LibraryLayout.LIST, state().layout)
    }

    @Test
    fun `choosing a layout in the detail writes it to the settings`() = runTest {
        val s = Setup(LibraryView.AUTHORS, "amy", listOf(testBook("a", author = "Amy")))
        val state = collect(s)

        s.vm.onLayoutSelected(com.chapterplayer.data.settings.LibraryLayout.SMALL_GRID)

        assertEquals(com.chapterplayer.data.settings.LibraryLayout.SMALL_GRID, s.settings.layout.value)
        assertEquals(com.chapterplayer.data.settings.LibraryLayout.SMALL_GRID, state().layout)
    }

    @Test
    fun `a group key survives the trip through a route`() {
        listOf("amy", "s1/c1", "Ünï cödé & more?#%/ ", "unknown").forEach { key ->
            val encoded = encodeGroupKey(key)
            assertTrue(encoded.all { it in '0'..'9' || it in 'a'..'f' }, "only hex characters: $encoded")
            assertEquals(key, decodeGroupKey(encoded))
        }
    }

    @Test
    fun `a damaged route key decodes to null`() {
        assertNull(decodeGroupKey("abc"))
        assertNull(decodeGroupKey("zz"))
    }
}
