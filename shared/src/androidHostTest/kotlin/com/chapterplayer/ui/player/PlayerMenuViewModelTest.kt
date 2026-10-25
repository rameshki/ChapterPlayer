package com.chapterplayer.ui.player

import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.playback.MarkAction
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakePlaybackController
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
class PlayerMenuViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val book = testBook("a", author = "Ann Author", seriesName = "Saga")
    private val sibling = testBook("b", author = "Ann Author", seriesName = "Saga")

    private class Rig(books: List<com.chapterplayer.data.model.AudioBook>) {
        val controller = FakePlaybackController()
        val repo = FakeBookRepository(books)
        val overview = FakeDownloadOverview()
        val downloads = FakeDownloadController()
        val connectivity = FakeConnectivity(true)
        val vm = PlayerMenuViewModel(controller, repo, overview, downloads, connectivity, Dispatchers.Main)
    }

    private fun rig(vararg books: com.chapterplayer.data.model.AudioBook) = Rig(books.toList())

    private fun TestScope.collect(r: Rig): () -> PlayerMenuState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.vm.state.collect {} }
        return { r.vm.state.value }
    }

    private fun load(r: Rig, id: String = "a") {
        r.controller.playback.value = PlaybackState(bookId = id, durationMs = 1_000L)
    }

    private fun progress(finished: Boolean) = ReadingProgress("a", if (finished) 1_000L else 400L, 1_000L, 50L, finished)

    // ---- what the menu offers ----

    @Test
    fun `nothing loaded offers nothing`() = runTest {
        val r = rig(book)
        val state = collect(r)
        assertNull(state().bookId)
        assertNull(state().mark)
        assertEquals(MenuDownloadKind.HIDDEN, state().download.kind)
        assertNull(state().series)
        assertNull(state().moreBy)
    }

    @Test
    fun `a book with a series and other books by its author offers both links`() = runTest {
        val r = rig(book, sibling)
        val state = collect(r)
        load(r)
        assertEquals("a", state().bookId)
        assertEquals("More by Ann Author", state().moreByLabel)
        assertEquals("ann author", state().moreBy!!.key)
        assertEquals("Saga", state().series!!.title)
    }

    @Test
    fun `a book without a series or other books by its author offers neither link`() = runTest {
        val r = rig(testBook("a", author = "Solo Author"), testBook("b", author = "Other"))
        val state = collect(r)
        load(r)
        assertNull(state().series)
        assertNull(state().moreBy)
        assertNull(state().moreByLabel)
    }

    @Test
    fun `a Plex book hides Download but offers Mark as finished`() = runTest {
        val r = rig(book.copy(source = ServerSource.PLEX))
        val state = collect(r)
        load(r)
        assertEquals(MenuDownloadKind.HIDDEN, state().download.kind)
        assertEquals(MarkAction.MarkFinished, state().mark)
    }

    @Test
    fun `mark follows the book's progress`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        assertEquals(MarkAction.MarkFinished, state().mark)
        r.repo.progress.value = listOf(progress(finished = false))
        assertEquals(MarkAction.MarkFinished, state().mark)
        r.repo.progress.value = listOf(progress(finished = true))
        assertEquals(MarkAction.MarkNotFinished, state().mark)
    }

    // ---- download states ----

    @Test
    fun `download item shows the state of the book's download`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        assertEquals(MenuDownloadKind.DOWNLOAD, state().download.kind)
        assertEquals("Download", state().download.label)
        assertTrue(state().download.enabled)

        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.QUEUED))
        assertEquals(MenuDownloadKind.PAUSE, state().download.kind)
        assertEquals("Pause download", state().download.label)

        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DOWNLOADING, fraction = 0.4f, etaSeconds = 600))
        assertEquals(MenuDownloadKind.PAUSE, state().download.kind)
        assertTrue(state().download.subtitle!!.startsWith("40%"))

        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.PAUSED, fraction = 0.4f))
        assertEquals(MenuDownloadKind.RESUME, state().download.kind)
        assertEquals("Resume download", state().download.label)

        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.FAILED, error = "The download failed"))
        assertEquals(MenuDownloadKind.TRY_AGAIN, state().download.kind)

        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, fraction = 1f, totalBytes = 1_000_000))
        assertEquals(MenuDownloadKind.REMOVE, state().download.kind)
        assertEquals("Remove download", state().download.label)
        assertTrue(state().download.subtitle!!.startsWith("Downloaded"))
    }

    @Test
    fun `starting a download needs a connection, removing does not`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.connectivity.online.value = false
        assertFalse(state().download.enabled)
        assertEquals("Needs a connection to download", state().download.subtitle)

        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, fraction = 1f))
        assertTrue(state().download.enabled)
    }

    // ---- download actions ----

    @Test
    fun `the download item starts, pauses, resumes and retries`() = runTest {
        val r = rig(book)
        collect(r)
        load(r)
        r.vm.onDownloadClicked()
        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DOWNLOADING))
        r.vm.onDownloadClicked()
        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.PAUSED))
        r.vm.onDownloadClicked()
        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.FAILED))
        r.vm.onDownloadClicked()
        assertEquals(listOf("enqueue a", "pause a", "resume a", "resume a"), r.downloads.calls)
    }

    @Test
    fun `a disabled download does nothing`() = runTest {
        val r = rig(book)
        collect(r)
        load(r)
        r.connectivity.online.value = false
        r.vm.onDownloadClicked()
        assertTrue(r.downloads.calls.isEmpty())
    }

    @Test
    fun `remove download asks first and only the confirmation removes`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, fraction = 1f))

        r.vm.onDownloadClicked()
        assertTrue(state().confirmingRemove)
        assertTrue(r.downloads.calls.isEmpty())

        r.vm.onRemoveDismissed()
        assertFalse(state().confirmingRemove)
        assertTrue(r.downloads.calls.isEmpty())

        r.vm.onDownloadClicked()
        r.vm.onRemoveConfirmed()
        assertFalse(state().confirmingRemove)
        assertEquals(listOf("remove a"), r.downloads.calls)
    }

    @Test
    fun `a pending remove confirmation is dropped when the book or the download changes`() = runTest {
        val r = rig(book, sibling)
        val state = collect(r)
        load(r)
        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, fraction = 1f), downloadRow("b", DownloadStatus.DONE, fraction = 1f))
        r.vm.onDownloadClicked()
        assertTrue(state().confirmingRemove)

        // another book loads: the question was about the first one
        load(r, "b")
        load(r, "a")
        assertFalse(state().confirmingRemove)

        // the download disappears and comes back: no stale question
        r.vm.onDownloadClicked()
        assertTrue(state().confirmingRemove)
        r.overview.rows.value = emptyList()
        r.overview.rows.value = listOf(downloadRow("a", DownloadStatus.DONE, fraction = 1f))
        assertFalse(state().confirmingRemove)
    }

    @Test
    fun `a failed download call shows an error message`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.downloads.failWith = IllegalStateException("secret detail")
        r.vm.onDownloadClicked()
        assertEquals("Could not change the download. Try again.", state().message)
    }

    // ---- mark as finished ----

    @Test
    fun `mark as finished calls the repository and confirms`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)

        r.vm.onMarkClicked()

        assertEquals(listOf("a" to true), r.repo.markCalls)
        assertEquals("Marked as finished", state().message)
    }

    @Test
    fun `mark as not finished calls the repository with false and confirms`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.repo.progress.value = listOf(progress(finished = true))

        r.vm.onMarkClicked()

        assertEquals(listOf("a" to false), r.repo.markCalls)
        assertEquals("Marked as not finished", state().message)
    }

    @Test
    fun `a mark that waits for the server says so`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.repo.onMarkFinished = { _, _ -> Result.success(MarkOutcome.QUEUED) }

        r.vm.onMarkClicked()

        assertEquals("Marked as finished. It will reach the server when it is back.", state().message)
    }

    @Test
    fun `a failed mark shows an error and never the exception text`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.repo.onMarkFinished = { _, _ -> Result.failure(IllegalStateException("http://secret.test token")) }

        r.vm.onMarkClicked()

        assertEquals("Could not update the book. Try again.", state().message)
    }

    @Test
    fun `a throwing repository is an error message too`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.repo.onMarkFinished = { _, _ -> throw IllegalStateException("boom") }

        r.vm.onMarkClicked()

        assertEquals("Could not update the book. Try again.", state().message)
    }

    @Test
    fun `mark does nothing when nothing is loaded, and marks a loaded Plex book`() = runTest {
        val r = rig(book.copy(source = ServerSource.PLEX))
        collect(r)
        r.vm.onMarkClicked()
        assertTrue(r.repo.markCalls.isEmpty())
        load(r)
        r.vm.onMarkClicked()
        assertEquals(listOf("a" to true), r.repo.markCalls)
    }

    @Test
    fun `a shown message is cleared`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        r.vm.onMarkClicked()
        r.vm.onMessageShown()
        assertNull(state().message)
    }
    // ---- a book on several servers ----

    private class Splits : com.chapterplayer.data.merge.BookMergeRepository {
        val calls = mutableListOf<String>()
        override val overrides = kotlinx.coroutines.flow.MutableStateFlow(com.chapterplayer.domain.browse.MergeOverrides())
        override suspend fun split(bookId: String, otherIds: Collection<String>) { calls += "split $bookId $otherIds" }
        override suspend fun merge(bookId: String, otherId: String) {}
    }

    @Test
    fun `the playing copy of a merged book can be split from the other`() = runTest {
        val a = testBook("a", title = "Same").copy(serverId = "abs1", durationMs = 3_600_000L)
        val p = a.copy(id = "p", serverId = "plex1", source = com.chapterplayer.data.model.ServerSource.PLEX)
        val controller = FakePlaybackController()
        val splits = Splits()
        val vm = PlayerMenuViewModel(
            controller, FakeBookRepository(listOf(a, p)), FakeDownloadOverview(), FakeDownloadController(), FakeConnectivity(true), Dispatchers.Main,
            merging = com.chapterplayer.data.merge.FixedSourceMerging(), merges = splits
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        controller.playback.value = PlaybackState(bookId = "p", durationMs = 1_000L)
        assertTrue(vm.state.value.canSplit)
        vm.onNotSameBookClicked()
        assertEquals(listOf("split p [a]"), splits.calls)
        assertEquals("Shown as separate books", vm.state.value.message)
    }

    @Test
    fun `a book on one server offers no split`() = runTest {
        val r = rig(book)
        val state = collect(r)
        load(r)
        assertFalse(state().canSplit)
    }
}
