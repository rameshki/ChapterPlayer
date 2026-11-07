package com.chapterplayer.ui.library

import com.chapterplayer.data.favourites.InMemoryFavourites
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.upnext.InMemoryUpNext
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BulkAction
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.downloadRow
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.FakeNotificationPermission
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class BookSelectionViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun item(id: String, serverId: String = "abs1", finished: Boolean? = null, download: DownloadState = DownloadState.NOT_DOWNLOADED) =
        BrowseItem(
            testBook(id).copy(serverId = serverId, source = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS),
            finished?.let { ReadingProgress(id, 10L, 100L, 5L, isFinished = it) },
            download,
        )

    private class Setup(favourites: Set<String> = emptySet(), queue: List<String> = emptyList(), context: MergeContext? = null) {
        val repo = FakeBookRepository()
        val downloads = FakeDownloadController()
        val notifications = FakeNotificationPermission()
        val player = FakePlaybackController()
        val connectivity = FakeConnectivity()
        val favsStore = InMemoryFavourites(favourites)
        val upNext = InMemoryUpNext(queue)
        val contextFlow = MutableStateFlow(context)
        val overview = FakeDownloadOverview()
        var writes = 0
        val favs = object : com.chapterplayer.data.favourites.FavouritesRepository by favsStore {
            override suspend fun add(bookIds: Collection<String>) { writes++; favsStore.add(bookIds) }
            override suspend fun remove(bookIds: Collection<String>) { writes++; favsStore.remove(bookIds) }
        }
        fun vm(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main) = BookSelectionViewModel(
            repo, downloads, notifications, player, connectivity, dispatcher, overview = overview,
            merging = object : SourceMerging { override val context = contextFlow },
            favourites = favs, upNext = upNext,
        )
    }

    @Test
    fun `select from the menu starts the mode with that book and taps toggle`() = runTest {
        val s = Setup()
        val vm = s.vm()
        vm.onItemsChanged(listOf(item("a"), item("b"), item("c")))
        vm.start("b")
        assertTrue(vm.uiState.value.active)
        assertEquals(setOf("b"), vm.uiState.value.selected)
        vm.toggle("c")
        vm.toggle("b")
        assertEquals(setOf("c"), vm.uiState.value.selected)
        assertEquals("1 selected", vm.uiState.value.title)
    }

    @Test
    fun `select all takes the list as shown and close leaves the mode`() = runTest {
        val vm = Setup().vm()
        vm.onItemsChanged(listOf(item("a"), item("b")))
        vm.start()
        vm.selectAll()
        assertEquals(setOf("a", "b"), vm.uiState.value.selected)
        vm.close()
        assertFalse(vm.uiState.value.active)
        assertEquals(emptySet(), vm.uiState.value.selected)
    }

    @Test
    fun `books that leave the list leave the selection`() = runTest {
        val vm = Setup().vm()
        vm.onItemsChanged(listOf(item("a"), item("b")))
        vm.start("a")
        vm.toggle("b")
        vm.onItemsChanged(listOf(item("b")))
        assertEquals(setOf("b"), vm.uiState.value.selected)
    }

    @Test
    fun `mark as finished marks each book not finished yet and closes with one message`() = runTest {
        val s = Setup()
        s.repo.onMarkFinished = { id, _ -> if (id == "c") Result.success(MarkOutcome.QUEUED) else Result.success(MarkOutcome.SYNCED) }
        val vm = s.vm()
        vm.onItemsChanged(listOf(item("a"), item("b", finished = true), item("c", finished = false)))
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.MARK_FINISHED)
        advanceUntilIdle()
        assertEquals(listOf("a" to true, "c" to true), s.repo.markCalls.sortedBy { it.first })
        assertEquals("Marked 2 books as finished. 1 already were. 1 could not reach their server and will sync when it is back", vm.message.value)
        assertFalse(vm.uiState.value.active)
    }

    @Test
    fun `mark as finished keeps at most three books going at once and shows the progress`() = runTest {
        val s = Setup()
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var most = 0
        s.repo.onMarkFinished = { _, _ ->
            running++
            most = maxOf(most, running)
            gate.await()
            running--
            Result.success(MarkOutcome.SYNCED)
        }
        val vm = s.vm(StandardTestDispatcher(testScheduler))
        vm.onItemsChanged((1..10).map { item("b$it") })
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.MARK_FINISHED)
        advanceUntilIdle()
        assertEquals(3, most)
        assertEquals("Marking 0 of 10…", vm.uiState.value.title)
        assertTrue(vm.uiState.value.running)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Marked 10 books as finished", vm.message.value)
        assertEquals(3, most)
    }

    @Test
    fun `cancel stops further books and keeps those done`() = runTest {
        val s = Setup()
        val gate = CompletableDeferred<Unit>()
        s.repo.onMarkFinished = { _, _ ->
            gate.await()
            Result.success(MarkOutcome.SYNCED)
        }
        val vm = s.vm(StandardTestDispatcher(testScheduler))
        vm.onItemsChanged((1..5).map { item("b$it") })
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.MARK_FINISHED)
        advanceUntilIdle()
        vm.cancel()
        assertEquals("Stopping…", vm.uiState.value.title)
        gate.complete(Unit)
        advanceUntilIdle()
        // b1, b2 and b3 had started (three at a time); b4 and b5 never did.
        assertEquals(listOf("b1", "b2", "b3"), s.repo.markCalls.map { it.first }.sorted())
        assertEquals("Stopped after 3 of 5. Marked 3 books as finished", vm.message.value)
        assertFalse(vm.uiState.value.active)
    }

    @Test
    fun `favourites add when any is not one and remove when all are`() = runTest {
        val s = Setup(favourites = setOf("a"))
        val vm = s.vm()
        vm.onItemsChanged(listOf(item("a"), item("b")))
        vm.start()
        vm.selectAll()
        assertEquals(BulkAction.ADD_FAVOURITES, vm.uiState.value.favouriteAction)
        vm.run(BulkAction.ADD_FAVOURITES)
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), s.favsStore.ids.value)
        assertEquals("Added 1 book to favourites. 1 already were", vm.message.value)
        vm.start()
        vm.selectAll()
        assertEquals(BulkAction.REMOVE_FAVOURITES, vm.uiState.value.favouriteAction)
        vm.run(BulkAction.REMOVE_FAVOURITES)
        advanceUntilIdle()
        assertEquals(emptySet(), s.favsStore.ids.value)
        // One write for the whole selection each time, not one per book.
        assertEquals(2, s.writes)
    }

    @Test
    fun `add to up next keeps the order shown`() = runTest {
        val s = Setup(queue = listOf("q"))
        val vm = s.vm()
        vm.onItemsChanged(listOf(item("c"), item("a"), item("b")))
        vm.start("b")
        vm.toggle("c")
        vm.run(BulkAction.ADD_UP_NEXT)
        advanceUntilIdle()
        assertEquals(listOf("q", "c", "b"), s.upNext.ids.value)
        assertEquals("Added 2 books to Up next", vm.message.value)
    }

    @Test
    fun `download takes the chosen copies and skips books that need a choice`() = runTest {
        val s = Setup(context = MergeContext(downloadChoice = SourceChoice.Ask, onlineServerIds = setOf("abs1", "plex1")))
        val vm = s.vm()
        val merged = item("m").let { it.copy(sources = listOf(it, item("p", "plex1"))) }
        vm.onItemsChanged(listOf(item("a"), merged, item("d", download = DownloadState.DOWNLOADED)))
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.DOWNLOAD)
        advanceUntilIdle()
        assertEquals(listOf("enqueue a"), s.downloads.calls)
        assertEquals(1, s.notifications.asks)
        assertEquals(
            "Downloading 1 book. 1 already downloaded or on its way. Skipped 1 that need you to choose a server (open each book to choose)",
            vm.message.value,
        )
    }

    @Test
    fun `remove download asks once and then removes every downloaded copy`() = runTest {
        val s = Setup()
        val vm = s.vm()
        vm.onItemsChanged(listOf(item("a", download = DownloadState.DOWNLOADED), item("b"), item("c", download = DownloadState.DOWNLOADED)))
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.REMOVE_DOWNLOAD)
        assertEquals(2, vm.uiState.value.confirm?.count)
        assertEquals("Remove 2 downloads?", vm.uiState.value.confirm?.title)
        assertTrue(s.downloads.calls.isEmpty())
        vm.confirmPending()
        advanceUntilIdle()
        assertEquals(listOf("remove a", "remove c"), s.downloads.calls)
        assertEquals("Removed 2 downloads. 1 had no download", vm.message.value)
        assertNull(vm.uiState.value.confirm)
    }

    @Test
    fun `back while an action runs cancels it instead of closing`() = runTest {
        val s = Setup()
        val gate = CompletableDeferred<Unit>()
        s.repo.onMarkFinished = { _, _ -> gate.await(); Result.success(MarkOutcome.SYNCED) }
        val vm = s.vm(StandardTestDispatcher(testScheduler))
        vm.onItemsChanged(listOf(item("a")))
        vm.start("a")
        vm.run(BulkAction.MARK_FINISHED)
        advanceUntilIdle()
        vm.close()
        assertTrue(vm.uiState.value.active)
        assertTrue(vm.uiState.value.progress!!.stopping)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.active)
    }

    @Test
    fun `a large selection runs to the end`() = runTest {
        val s = Setup()
        val vm = s.vm()
        vm.onItemsChanged((1..1000).map { item("b$it") })
        vm.start()
        vm.selectAll()
        assertEquals("1000 selected", vm.uiState.value.title)
        vm.run(BulkAction.MARK_FINISHED)
        advanceUntilIdle()
        assertEquals(1000, s.repo.markCalls.size)
        assertEquals("Marked 1000 books as finished", vm.message.value)
    }

    @Test
    fun `download resumes paused and failed downloads by their real status and remove takes partial ones`() = runTest {
        val s = Setup()
        // The list reads both as DOWNLOADING; the rows say paused and failed.
        s.overview.rows.value = listOf(
            downloadRow("p", DownloadStatus.PAUSED), downloadRow("f", DownloadStatus.FAILED), downloadRow("r", DownloadStatus.DOWNLOADING),
        )
        val vm = s.vm()
        val items = listOf(
            item("p", download = DownloadState.DOWNLOADING), item("f", download = DownloadState.DOWNLOADING),
            item("r", download = DownloadState.DOWNLOADING), item("n"),
        )
        vm.onItemsChanged(items)
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.DOWNLOAD)
        advanceUntilIdle()
        assertEquals(listOf("resume p", "resume f", "enqueue n"), s.downloads.calls)
        assertEquals("Downloading 3 books. 2 of them carry on where they stopped. 1 already downloaded or on its way", vm.message.value)
        s.downloads.calls.clear()
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.REMOVE_DOWNLOAD)
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()
        assertEquals(listOf("remove p", "remove f", "remove r"), s.downloads.calls)
    }

    @Test
    fun `download leaves merged books out while the download choice is not known`() = runTest {
        val s = Setup(context = null)
        val vm = s.vm()
        val merged = item("m").let { it.copy(sources = listOf(it, item("x", "plex1"))) }
        vm.onItemsChanged(listOf(item("a"), merged))
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.DOWNLOAD)
        advanceUntilIdle()
        assertEquals(listOf("enqueue a"), s.downloads.calls)
    }

    @Test
    fun `mark as not finished asks first above five books and counts only finished ones`() = runTest {
        val s = Setup()
        val vm = s.vm()
        vm.onItemsChanged((1..6).map { item("f$it", finished = true) } + item("open", finished = false))
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.MARK_NOT_FINISHED)
        advanceUntilIdle()
        assertEquals("Mark 6 books as not finished?", vm.uiState.value.confirm?.title)
        assertTrue(s.repo.markCalls.isEmpty())
        vm.dismissPending()
        assertNull(vm.uiState.value.confirm)
        assertTrue(vm.uiState.value.active)
        vm.run(BulkAction.MARK_NOT_FINISHED)
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()
        assertEquals(6, s.repo.markCalls.size)
        assertEquals("Marked 6 books as not finished. 1 already were", vm.message.value)
    }

    @Test
    fun `five books or fewer are marked not finished without a question`() = runTest {
        val s = Setup()
        val vm = s.vm()
        vm.onItemsChanged((1..5).map { item("f$it", finished = true) })
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.MARK_NOT_FINISHED)
        advanceUntilIdle()
        assertNull(vm.uiState.value.confirm)
        assertEquals(5, s.repo.markCalls.size)
    }

    @Test
    fun `progress is announced only every tenth of the way`() = runTest {
        val s = Setup()
        val gate = CompletableDeferred<Unit>()
        s.repo.onMarkFinished = { id, _ -> if (id == "b51") gate.await(); Result.success(MarkOutcome.SYNCED) }
        val vm = s.vm(StandardTestDispatcher(testScheduler))
        vm.onItemsChanged((1..100).map { item("b$it") })
        vm.start()
        vm.selectAll()
        vm.run(BulkAction.MARK_FINISHED)
        advanceUntilIdle()
        val state = vm.uiState.value
        // Three at a time: b51 holds one worker, the others run on to the end.
        assertTrue(state.progress!!.done in 50..99)
        assertEquals("Marking ${state.progress!!.done / 10 * 10} of 100…", state.announcement)
        gate.complete(Unit)
        advanceUntilIdle()
    }
}
