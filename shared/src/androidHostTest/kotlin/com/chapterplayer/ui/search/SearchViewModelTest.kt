package com.chapterplayer.ui.search

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.domain.browse.queryFor
import com.chapterplayer.domain.browse.coverOverlay
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeMonitor : ConnectivityMonitor {
        override val isOnline: Flow<Boolean> = MutableStateFlow(true)
    }

    private val books = listOf(
        testBook("t", title = "Dune", author = "Frank Herbert", narrator = "Scott Brick"),
        testBook("n", title = "Other", author = "Someone", narrator = "Simon Vance"),
        testBook("s", title = "Third", author = "Else", narrator = "Else", seriesName = "Discworld")
    )

    private class Setup(books: List<AudioBook>) {
        val repo = FakeBookRepository(books)
        val store = BrowseQueryStore()
        val vm = SearchViewModel(repo, FakeDownloadStates(), store, FakeMonitor(), FakeAppSettings(), Dispatchers.Main)
    }

    private fun TestScope.collect(s: Setup): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }

    private fun TestScope.search(s: Setup, text: String) {
        s.vm.onQueryChange(text)
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
        runCurrent()
    }

    private fun Setup.ids() = vm.uiState.value.results.map { it.book.id }

    @Test
    fun `an empty query gives the prompt`() = runTest {
        val s = Setup(books)
        collect(s)
        runCurrent()

        assertEquals(SearchStatus.PROMPT, s.vm.uiState.value.status)
        assertEquals(emptyList(), s.ids())
    }

    @Test
    fun `a title, author, narrator or series match finds the book`() = runTest {
        val s = Setup(books)
        collect(s)

        search(s, "dune"); assertEquals(listOf("t"), s.ids())
        search(s, "someone"); assertEquals(listOf("n"), s.ids())
        search(s, "brick"); assertEquals(listOf("t"), s.ids())
        search(s, "disc"); assertEquals(listOf("s"), s.ids())
        assertEquals(SearchStatus.RESULTS, s.vm.uiState.value.status)
    }

    @Test
    fun `a query with no match says there are no results`() = runTest {
        val s = Setup(books)
        collect(s)

        search(s, "zzz")

        assertEquals(SearchStatus.NO_RESULTS, s.vm.uiState.value.status)
        assertEquals("zzz", s.vm.uiState.value.query)
    }

    @Test
    fun `the Downloaded chip is reported when it hides the matches`() = runTest {
        val s = Setup(books)
        collect(s)
        search(s, "dune")
        assertFalse(s.vm.uiState.value.downloadedFilterOn)

        s.store.update { queryFor(FilterChip.DOWNLOADED, it) }
        runCurrent()

        assertEquals(SearchStatus.FILTERED_OUT, s.vm.uiState.value.status)
        assertTrue(s.vm.uiState.value.downloadedFilterOn)
    }

    @Test
    fun `a filter that hides the matches is told apart from no matches`() = runTest {
        val s = Setup(books)
        collect(s)
        search(s, "dune")

        s.store.update { it.copy(progress = ProgressFilter.FINISHED) }
        runCurrent()

        assertEquals(SearchStatus.FILTERED_OUT, s.vm.uiState.value.status)
        assertEquals(emptyList(), s.ids())
    }

    @Test
    fun `filters from the store apply to the matches`() = runTest {
        val s = Setup(listOf(testBook("a", title = "Alpha"), testBook("b", title = "Alpine")))
        s.repo.progress.value = listOf(ReadingProgress("a", 100, 1_000, 5))
        collect(s)
        search(s, "al")

        s.store.update { it.copy(progress = ProgressFilter.IN_PROGRESS) }
        runCurrent()

        assertEquals(listOf("a"), s.ids())
    }

    @Test
    fun `results update when the book list changes`() = runTest {
        val s = Setup(books)
        collect(s)
        search(s, "dune")

        s.repo.books.value = books + testBook("d2", title = "Dune Messiah")
        runCurrent()

        assertEquals(setOf("t", "d2"), s.ids().toSet())
    }

    @Test
    fun `the query text survives losing and regaining the collector`() = runTest {
        val s = Setup(books)
        val first = collect(s)
        search(s, "dune")

        first.cancel()
        advanceTimeBy(60_000)
        collect(s)
        runCurrent()

        assertEquals("dune", s.vm.queryText.value)
        assertEquals("dune", s.vm.uiState.value.query)
        assertEquals(listOf("t"), s.ids())
    }

    @Test
    fun `typing is debounced`() = runTest {
        val s = Setup(books)
        collect(s)
        runCurrent()

        s.vm.onQueryChange("d")
        advanceTimeBy(100)
        s.vm.onQueryChange("du")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        assertEquals(SearchStatus.PROMPT, s.vm.uiState.value.status)
        assertEquals("du", s.vm.queryText.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals("du", s.vm.uiState.value.query)
        assertEquals(listOf("t"), s.ids())
    }

    @Test
    fun `a finished book matches the finished filter and is left out of not started`() = runTest {
        val s = Setup(listOf(testBook("a", title = "Alpha"), testBook("b", title = "Alpine")))
        s.repo.progress.value = listOf(ReadingProgress("a", 1_000, 1_000, 5, isFinished = true))
        collect(s)
        search(s, "al")

        s.store.update { it.copy(progress = ProgressFilter.FINISHED) }
        runCurrent()
        assertEquals(listOf("a"), s.ids())

        s.store.update { it.copy(progress = ProgressFilter.NOT_STARTED) }
        runCurrent()
        assertEquals(listOf("b"), s.ids())
    }

    @Test
    fun `the finished check shows on a finished result`() = runTest {
        val s = Setup(listOf(testBook("a", title = "Alpha")))
        s.repo.progress.value = listOf(ReadingProgress("a", 1_000, 1_000, 5, isFinished = true))
        collect(s)
        search(s, "alpha")

        val item = s.vm.uiState.value.results.single()
        assertEquals(true, coverOverlay(item.progress, item.downloadState, dimmed = false).finished)
    }

    @Test
    fun `the query is trimmed and several words must all match`() = runTest {
        val s = Setup(books)
        collect(s)

        search(s, "  frank   dune  "); assertEquals(listOf("t"), s.ids())
        search(s, "frank vance"); assertEquals(emptyList(), s.ids())
        assertEquals(SearchStatus.NO_RESULTS, s.vm.uiState.value.status)
        search(s, "   "); assertEquals(SearchStatus.PROMPT, s.vm.uiState.value.status)
    }

    @Test
    fun `clearing the query shows the prompt at once`() = runTest {
        val s = Setup(books)
        collect(s)
        search(s, "dune")

        s.vm.onQueryChange("")
        runCurrent()

        assertEquals(SearchStatus.PROMPT, s.vm.uiState.value.status)
    }
}
