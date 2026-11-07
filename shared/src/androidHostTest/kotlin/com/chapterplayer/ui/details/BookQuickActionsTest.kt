package com.chapterplayer.ui.details

import com.chapterplayer.data.favourites.InMemoryFavourites
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.playback.MarkAction
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakeNotificationPermission
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.downloadRow
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

/** The book long-press menu runs on the details view model with `quickActions`: favourites, marks, the reported results. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookQuickActionsTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun abs(id: String) = testBook(id, title = "Same Book", author = "Same Author").copy(serverId = "abs1", durationMs = 3_600_000L)
    private fun plex(id: String) = abs(id).copy(serverId = "plex1", source = ServerSource.PLEX)

    private class Setup(books: List<AudioBook>, id: String, quick: Boolean = true, favourites: Set<String> = emptySet(),
                        rows: List<com.chapterplayer.data.downloads.DownloadRow> = emptyList()) {
        var detailsLoads = 0
        val repo = object : FakeBookRepository(books) {
            override suspend fun loadDetails(bookId: String): Result<BookDetails?> {
                detailsLoads++
                return Result.success(null)
            }
        }
        val controller = FakePlaybackController()
        val settings = FakeAppSettings()
        val connectivity = FakeConnectivity()
        val downloads = FakeDownloadController()
        val favs = InMemoryFavourites(favourites)
        val context = kotlinx.coroutines.flow.MutableStateFlow(
            MergeContext(MergeOverrides(), SourceChoice.Ask, SourceChoice.Ask, setOf("abs1", "plex1"), true)
        )
        val vm = BookDetailsViewModel(
            id, repo, FakeDownloadStates(), controller,
            PlaybackStarter(controller, repo, settings, connectivity, FakeLocalBookFiles()), connectivity, settings, Dispatchers.Main,
            FakeDownloadOverview(rows), downloads, FakeNotificationPermission(),
            merging = object : com.chapterplayer.data.merge.SourceMerging {
                override val context = this@Setup.context
            },
            servers = FakeServerList(listOf(
                ServerConfig("abs1", ServerSource.ABS, "Home ABS", "http://a", "t"),
                ServerConfig("plex1", ServerSource.PLEX, "Home Plex", "http://p", "t"),
            )),
            favourites = favs,
            quickActions = quick,
        )
    }

    private fun TestScope.collect(s: Setup): () -> BookDetailsUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value }
    }

    @Test
    fun `the menu fetches nothing from the server on open and the page does`() = runTest {
        val quick = Setup(listOf(abs("a")), "a")
        collect(quick)
        assertEquals(0, quick.detailsLoads)
        val page = Setup(listOf(abs("a")), "a", quick = false)
        collect(page)
        assertEquals(1, page.detailsLoads)
    }

    @Test
    fun `a merged book is a favourite when either copy is and the heart unmarks both`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", favourites = setOf("p"))
        val state = collect(s)
        assertTrue(state().favourite)
        s.vm.onFavouriteClicked()
        assertEquals(emptySet(), s.favs.ids.value)
        assertFalse(state().favourite)
        assertEquals("Removed from favourites", s.vm.message.value)
        s.vm.onMessageShown()
        s.vm.onFavouriteClicked()
        assertEquals(setOf("a", "p"), s.favs.ids.value)
        assertEquals("Added to favourites", s.vm.message.value)
    }

    @Test
    fun `the page's heart changes the favourite without a message`() = runTest {
        val s = Setup(listOf(abs("a")), "a", quick = false)
        val state = collect(s)
        s.vm.onFavouriteClicked()
        assertTrue(state().favourite)
        assertNull(s.vm.message.value)
    }

    @Test
    fun `mark follows the entry's progress and marks the copy played last`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a")
        s.repo.progress.value = listOf(
            ReadingProgress("a", 10L, 3_600_000L, lastPlayedAt = 100L),
            ReadingProgress("p", 3_600_000L, 3_600_000L, lastPlayedAt = 200L, isFinished = true),
        )
        val state = collect(s)
        assertEquals(MarkAction.MarkNotFinished, state().mark)
        s.vm.onMarkClicked()
        assertEquals(listOf("p" to false), s.repo.markCalls)
        assertEquals("Marked as not finished", s.vm.message.value)
    }

    @Test
    fun `a mark that waits for the server says so`() = runTest {
        val s = Setup(listOf(abs("a")), "a")
        s.repo.onMarkFinished = { _, _ -> Result.success(MarkOutcome.QUEUED) }
        val state = collect(s)
        assertEquals(MarkAction.MarkFinished, state().mark)
        s.vm.onMarkClicked()
        assertEquals(listOf("a" to true), s.repo.markCalls)
        assertEquals("Marked as finished. It will reach the server when it is back.", s.vm.message.value)
    }

    @Test
    fun `play on a merged book with Ask opens the play-from sheet`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a")
        val state = collect(s)
        assertTrue(s.vm.onQuickPlay())
        assertEquals(SourceAction.PLAY, state().sourceSheet?.action)
    }

    @Test
    fun `a play-from sheet left open never makes a later play look like a choice`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a")
        val state = collect(s)
        assertTrue(s.vm.onQuickPlay())
        // The menu gave up waiting for the sheet; meanwhile the play choice became Audiobookshelf.
        s.context.value = s.context.value.copy(playChoice = SourceChoice.OfType(ServerSource.ABS))
        assertFalse(s.vm.onQuickPlay())
        assertNull(state().sourceSheet)
        assertEquals("a", s.controller.state.value.bookId)
    }

    @Test
    fun `the heart writes the ids of the entry it shows`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", favourites = setOf("p"))
        val state = collect(s)
        assertEquals(listOf("a", "p"), state().favouriteCopyIds)
        assertTrue(state().favourite)
    }

    @Test
    fun `a book that is gone says so`() = runTest {
        val s = Setup(listOf(abs("a")), "a")
        val state = collect(s)
        s.repo.books.value = emptyList()
        assertEquals(DetailsPhase.NOT_FOUND, state().phase)
    }

    @Test
    fun `play on a book on one server starts it`() = runTest {
        val s = Setup(listOf(abs("a")), "a")
        collect(s)
        assertFalse(s.vm.onQuickPlay())
        assertEquals("a", s.controller.state.value.bookId)
    }

    @Test
    fun `download on a merged book with Ask opens the download-from sheet`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a")
        val state = collect(s)
        assertTrue(s.vm.onQuickDownload())
        assertEquals(SourceAction.DOWNLOAD, state().sourceSheet?.action)
        assertTrue(s.downloads.calls.isEmpty())
    }

    @Test
    fun `download on one server starts and reports it`() = runTest {
        val s = Setup(listOf(abs("a")), "a")
        collect(s)
        assertFalse(s.vm.onQuickDownload())
        assertEquals(listOf("enqueue a"), s.downloads.calls)
        assertEquals("Download started", s.vm.message.value)
    }

    @Test
    fun `a failed download change is reported`() = runTest {
        val s = Setup(listOf(abs("a")), "a", rows = listOf(downloadRow("a", DownloadStatus.DOWNLOADING)))
        collect(s)
        s.downloads.failWith = IllegalStateException()
        s.vm.onPauseDownloadClicked()
        assertEquals("Could not change the download. Try again.", s.vm.message.value)
    }
}
