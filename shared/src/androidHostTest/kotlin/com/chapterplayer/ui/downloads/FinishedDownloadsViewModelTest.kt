package com.chapterplayer.ui.downloads

import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.upnext.InMemoryUpNext
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.downloadRow
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FinishedDownloadsViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val gb = 1_000_000_000L

    private class Setup(queue: List<String> = emptyList()) {
        val repo = FakeBookRepository(listOf(testBook("a"), testBook("b"), testBook("c"), testBook("d")))
        val overview = FakeDownloadOverview()
        val controller = FakeDownloadController()
        val upNext = InMemoryUpNext(queue)
        val vm = FinishedDownloadsViewModel(
            repo, FakeDownloadStates(), overview, controller, FakePlaybackController(), Dispatchers.Main,
            FakeConnectivity(), FakeAppSettings(), upNext = upNext,
        )
    }

    // The row is on screen: its state is followed (the view model's flows run only then).
    private fun TestScope.shown(s: Setup) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
    }

    private fun finished(id: String) = ReadingProgress(id, 100L, 100L, 5L, isFinished = true)

    @Test
    fun `hidden when no finished book has a download`() = runTest {
        val s = Setup()
        shown(s)
        s.repo.progress.value = listOf(finished("a"))
        s.overview.rows.value = listOf(downloadRow("b", DownloadStatus.DONE, doneBytes = gb, totalBytes = gb))
        assertNull(s.vm.uiState.value.summary)
        s.vm.onRemoveClicked()
        assertNull(s.vm.uiState.value.question)
    }

    @Test
    fun `asks with the count and space and removes the finished downloads`() = runTest {
        val s = Setup(queue = listOf("c"))
        shown(s)
        s.repo.progress.value = listOf(finished("a"), finished("b"), finished("c"), ReadingProgress("d", 10L, 100L, 5L))
        s.overview.rows.value = listOf(
            downloadRow("a", DownloadStatus.DONE, doneBytes = 2 * gb, totalBytes = 2 * gb),
            downloadRow("b", DownloadStatus.DOWNLOADING, doneBytes = gb, totalBytes = 2 * gb),
            downloadRow("c", DownloadStatus.DONE, doneBytes = gb, totalBytes = gb),
            downloadRow("d", DownloadStatus.DONE, doneBytes = gb, totalBytes = gb),
        )
        assertEquals("1 download of a finished book · 2 GB", s.vm.uiState.value.summary)
        s.vm.onRemoveClicked()
        assertEquals("Remove 1 download and free 2 GB?", s.vm.uiState.value.question)
        s.vm.onConfirmed()
        advanceUntilIdle()
        assertEquals(listOf("remove a"), s.controller.calls)
        assertEquals("Removed 1 download and freed 2 GB", s.vm.message.value)
        assertNull(s.vm.uiState.value.question)
    }

    @Test
    fun `keep closes the question and removes nothing`() = runTest {
        val s = Setup()
        shown(s)
        s.repo.progress.value = listOf(finished("a"))
        s.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, doneBytes = gb, totalBytes = gb))
        s.vm.onRemoveClicked()
        s.vm.onDismissed()
        assertNull(s.vm.uiState.value.question)
        assertEquals(emptyList(), s.controller.calls)
    }

    @Test
    fun `a question left open by a change in the list does not come back by itself`() = runTest {
        val s = Setup()
        shown(s)
        s.repo.progress.value = listOf(finished("a"))
        s.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, doneBytes = gb, totalBytes = gb))
        s.vm.onRemoveClicked()
        assertEquals("Remove 1 download and free 1 GB?", s.vm.uiState.value.question)
        // A sync un-finishes the book: nothing qualifies, the question goes.
        s.repo.progress.value = listOf(ReadingProgress("a", 10L, 100L, 6L))
        assertNull(s.vm.uiState.value.question)
        // Later another book finishes: the row comes back, the question does not.
        s.repo.progress.value = listOf(finished("a"))
        assertEquals("1 download of a finished book · 1 GB", s.vm.uiState.value.summary)
        assertNull(s.vm.uiState.value.question)
    }
}
