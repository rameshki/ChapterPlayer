package com.chapterplayer.ui.downloads

import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeDownloadStorage
import com.chapterplayer.testing.downloadRow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Counts the free-space reads. */
    private class CountingStorage : DownloadStorage by FakeDownloadStorage() {
        var reads = 0
        override suspend fun freeBytes(): Long {
            reads++
            return 30_000_000_000
        }
    }

    private class Setup(online: Boolean = true, freeSpaceEveryMs: Long = 0) {
        val books = FakeBookRepository(listOf(testBook("a", title = "Alpha"), testBook("b", title = "Bravo"), testBook("c", title = "Charlie")))
        val overview = FakeDownloadOverview()
        val controller = FakeDownloadController()
        val storage = CountingStorage()
        val connectivity = FakeConnectivity(online)
        var now = 0L
        val vm = DownloadsViewModel(overview, books, controller, storage, connectivity, Dispatchers.Main, freeSpaceEveryMs, nowMs = { now })
    }

    private fun TestScope.collect(s: Setup): () -> DownloadsUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value }
    }

    @Test
    fun `no downloads is the empty state with the storage header`() = runTest {
        val s = Setup()
        val state = collect(s)
        assertFalse(state().loading)
        assertTrue(state().items.isEmpty())
        assertEquals("0 MB used · Free: 30 GB", state().headerText)
    }

    @Test
    fun `rows carry the book, the status line, and come active first`() = runTest {
        val s = Setup()
        val state = collect(s)
        s.overview.rows.value = listOf(
            downloadRow("a", DownloadStatus.DONE, fraction = 1f, totalBytes = 1_200_000_000),
            downloadRow("c", DownloadStatus.DOWNLOADING, fraction = 0.5f, etaSeconds = 600),
            downloadRow("b", DownloadStatus.QUEUED),
        )
        s.overview.used.value = 1_200_000_000

        assertEquals(listOf("b", "c", "a"), state().items.map { it.bookId })
        assertEquals(listOf("Queued", "50% · 10m left", "1.2 GB"), state().items.map { it.statusLine })
        assertEquals("Charlie", state().items[1].title)
        assertEquals(50, state().items[1].percent)
        assertEquals("1.2 GB used · Free: 30 GB", state().headerText)
    }

    @Test
    fun `paused and failed rows, the failure with its error`() = runTest {
        val s = Setup()
        val state = collect(s)
        s.overview.rows.value = listOf(
            downloadRow("a", DownloadStatus.PAUSED, fraction = 0.25f),
            downloadRow("b", DownloadStatus.FAILED, error = "Not enough space"),
        )
        assertEquals("Paused · 25%", state().items.single { it.bookId == "a" }.statusLine)
        val failed = state().items.single { it.bookId == "b" }
        assertEquals("Failed", failed.statusLine)
        assertEquals("Not enough space", failed.error)
    }

    @Test
    fun `a download whose book left the library still shows so it can be removed`() = runTest {
        val s = Setup()
        val state = collect(s)
        s.overview.rows.value = listOf(downloadRow("gone", DownloadStatus.DONE, fraction = 1f))
        val item = state().items.single()
        assertNull(item.coverUrl)
        assertEquals("Book no longer in the library", item.title)
        assertFalse(item.opensDetails)
        assertTrue(state().items.isNotEmpty())
    }

    @Test
    fun `rows of books in the library open their details`() = runTest {
        val s = Setup()
        val state = collect(s)
        s.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, fraction = 1f))
        assertTrue(state().items.single().opensDetails)
    }

    @Test
    fun `offline Try again is disabled and does nothing, pause and cancel still work`() = runTest {
        val s = Setup(online = false)
        val state = collect(s)
        s.overview.rows.value = listOf(downloadRow("a", DownloadStatus.FAILED, error = "x"))
        assertFalse(state().items.single().canRetry)

        s.vm.retry("a")
        s.vm.cancel("a")
        assertEquals(listOf("cancel a"), s.controller.calls)

        s.connectivity.online.value = true
        assertTrue(state().items.single().canRetry)
        s.vm.retry("a")
        assertEquals(listOf("cancel a", "resume a"), s.controller.calls)
    }

    @Test
    fun `the free space is read at most once per interval while the used bytes change`() = runTest {
        val s = Setup(freeSpaceEveryMs = 5_000)
        val state = collect(s)
        s.overview.used.value = 1_000_000_000
        s.overview.used.value = 2_000_000_000
        assertEquals(1, s.storage.reads)
        assertEquals("2 GB used · Free: 30 GB", state().headerText)

        s.now = 5_000
        s.overview.used.value = 3_000_000_000
        assertEquals(2, s.storage.reads)
    }

    @Test
    fun `the row buttons go to the controller`() = runTest {
        val s = Setup()
        collect(s)
        s.vm.pause("a")
        s.vm.resume("b")
        s.vm.cancel("c")
        assertEquals(listOf("pause a", "resume b", "cancel c"), s.controller.calls)
    }

    @Test
    fun `remove asks first and only the confirmation removes`() = runTest {
        val s = Setup()
        val state = collect(s)
        s.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, fraction = 1f))

        s.vm.askRemove("a")
        assertEquals("a", state().confirmRemove?.bookId)
        assertEquals("Alpha", state().confirmRemove?.title)
        s.vm.dismissRemove()
        assertNull(state().confirmRemove)
        assertTrue(s.controller.calls.isEmpty())

        s.vm.askRemove("a")
        s.vm.confirmRemove()
        assertNull(state().confirmRemove)
        assertEquals(listOf("remove a"), s.controller.calls)
    }

    @Test
    fun `a failing call leaves the list as it is`() = runTest {
        val s = Setup()
        val state = collect(s)
        s.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DOWNLOADING))
        s.controller.failWith = IllegalStateException("x")
        s.vm.pause("a")
        // The call was made and failed; the row is unchanged and the buttons keep working.
        assertEquals(listOf("pause a"), s.controller.calls)
        assertEquals(DownloadStatus.DOWNLOADING, state().items.single().status)
        s.controller.failWith = null
        s.vm.cancel("a")
        assertEquals(listOf("pause a", "cancel a"), s.controller.calls)
    }
}
