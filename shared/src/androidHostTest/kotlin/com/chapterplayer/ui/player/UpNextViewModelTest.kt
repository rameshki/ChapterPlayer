package com.chapterplayer.ui.player

import com.chapterplayer.data.merge.FixedSourceMerging
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.upnext.InMemoryUpNext
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class UpNextViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val a = testBook("a", title = "Alpha").copy(durationMs = 3_600_000L)
    private val b = testBook("b", title = "Beta").copy(durationMs = 90 * 60_000L)
    private val c = testBook("c", title = "Gamma").copy(durationMs = 0L)
    private val repo = FakeBookRepository(listOf(a, b, c))
    private val queue = InMemoryUpNext()

    private fun TestScope.viewModel(): UpNextViewModel {
        val vm = UpNextViewModel(repo, FakeDownloadStates(), queue, Dispatchers.Main, FixedSourceMerging(MergeContext()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `rows follow the queue with title author and length`() = runTest {
        queue.ids.value = listOf("b", "a", "c")
        val vm = viewModel()
        val rows = vm.uiState.value.rows
        assertEquals(listOf("Beta", "Alpha", "Gamma"), rows.map { it.book.title })
        assertEquals(listOf("1 h 30 min", "1 h", ""), rows.map { it.lengthText })
        assertFalse(rows.first().canMoveUp)
        assertTrue(rows.first().canMoveDown)
        assertFalse(rows.last().canMoveDown)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `an empty queue has no rows`() = runTest {
        val vm = viewModel()
        assertEquals(emptyList(), vm.uiState.value.rows)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `a book that is gone is not shown`() = runTest {
        queue.ids.value = listOf("gone", "a")
        val vm = viewModel()
        assertEquals(listOf("a"), vm.uiState.value.rows.map { it.queueId })
    }

    @Test
    fun `move up and down`() = runTest {
        queue.ids.value = listOf("a", "b", "c")
        val vm = viewModel()
        vm.onMoveDown("a")
        assertEquals(listOf("b", "a", "c"), queue.ids.value)
        vm.onMoveUp("c")
        assertEquals(listOf("b", "c", "a"), queue.ids.value)
        vm.onMoveUp("b")
        assertEquals(listOf("b", "c", "a"), queue.ids.value)
    }

    @Test
    fun `a move steps over a row that is not shown`() = runTest {
        queue.ids.value = listOf("a", "gone", "b")
        val vm = viewModel()
        vm.onMoveDown("a")
        assertEquals(listOf("gone", "b", "a"), queue.ids.value)
    }

    @Test
    fun `remove and clear all`() = runTest {
        queue.ids.value = listOf("a", "b", "c")
        val vm = viewModel()
        vm.onRemove("b")
        assertEquals(listOf("a", "c"), queue.ids.value)
        vm.onClearAll()
        assertEquals(emptyList(), queue.ids.value)
        assertEquals(emptyList(), vm.uiState.value.rows)
    }

    @Test
    fun `a merged book is one row and removing it removes every copy's row`() = runTest {
        val abs = testBook("m", title = "Same", author = "Same").copy(durationMs = 3_600_000L)
        val plex = abs.copy(id = "plex_m", serverId = "p1", source = ServerSource.PLEX)
        repo.books.value = listOf(a, abs, plex)
        queue.ids.value = listOf("plex_m", "a", "m")
        val vm = viewModel()
        assertEquals(listOf("plex_m", "a"), vm.uiState.value.rows.map { it.queueId })
        vm.onRemove("plex_m")
        assertEquals(listOf("a"), queue.ids.value)
    }
}
