package com.chapterplayer.ui.details

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.upnext.InMemoryUpNext
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.SourceChoice
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakeNotificationPermission
import com.chapterplayer.testing.FakePlaybackController
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
import kotlin.test.assertTrue

/** Play next and Add to Up next, from the long-press menu and the details page (the same view model). */
@OptIn(ExperimentalCoroutinesApi::class)
class BookUpNextActionsTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun abs(id: String) = testBook(id, title = "Same Book", author = "Same Author").copy(serverId = "abs1", durationMs = 3_600_000L)
    private fun plex(id: String) = abs(id).copy(serverId = "plex1", source = ServerSource.PLEX)

    private class Setup(books: List<AudioBook>, id: String, quick: Boolean = true, queue: UpNextRepository = InMemoryUpNext()) {
        val repo = object : FakeBookRepository(books) {
            override suspend fun loadDetails(bookId: String): Result<BookDetails?> = Result.success(null)
        }
        val controller = FakePlaybackController()
        val settings = FakeAppSettings()
        val connectivity = FakeConnectivity()
        val upNext = queue
        val vm = BookDetailsViewModel(
            id, repo, FakeDownloadStates(), controller,
            PlaybackStarter(controller, repo, settings, connectivity, FakeLocalBookFiles()), connectivity, settings, Dispatchers.Main,
            FakeDownloadOverview(), FakeDownloadController(), FakeNotificationPermission(),
            merging = object : com.chapterplayer.data.merge.SourceMerging {
                override val context = MutableStateFlow(MergeContext(MergeOverrides(), SourceChoice.Ask, SourceChoice.Ask, setOf("abs1", "plex1"), true))
            },
            upNext = queue,
            quickActions = quick,
        )
    }

    private fun TestScope.collect(s: Setup): () -> BookDetailsUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value }
    }

    private val Setup.ids get() = (upNext as InMemoryUpNext).ids

    @Test
    fun `play next puts the book at the front and says so`() = runTest {
        val s = Setup(listOf(abs("a"), testBook("x")), "a", queue = InMemoryUpNext(listOf("x")))
        val state = collect(s)
        s.vm.onUpNextClicked(front = true)
        assertEquals(listOf("a", "x"), s.ids.value)
        assertEquals("Plays next", s.vm.message.value)
        assertTrue(state().inUpNext)
    }

    @Test
    fun `add to up next puts the book at the end`() = runTest {
        val s = Setup(listOf(abs("a"), testBook("x")), "a", queue = InMemoryUpNext(listOf("x")))
        collect(s)
        s.vm.onUpNextClicked(front = false)
        assertEquals(listOf("x", "a"), s.ids.value)
        assertEquals("Added to Up next", s.vm.message.value)
    }

    @Test
    fun `a merged book is queued once whichever copy asks`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "p", queue = InMemoryUpNext(listOf("a")))
        val state = collect(s)
        assertTrue(state().inUpNext)
        s.vm.onUpNextClicked(front = false)
        assertEquals(listOf("a"), s.ids.value)
        assertEquals("Already in Up next", s.vm.message.value)
        s.vm.onUpNextClicked(front = true)
        assertEquals(listOf("p"), s.ids.value)
    }

    @Test
    fun `the book in the player is never queued`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a")
        val state = collect(s)
        s.controller.playback.value = PlaybackState(bookId = "p", isPlaying = true, playWhenReady = true, durationMs = 3_600_000L)
        assertTrue(state().inPlayer)
        s.vm.onUpNextClicked(front = true)
        assertEquals(emptyList(), s.ids.value)
        assertEquals("This book is playing now", s.vm.message.value)
    }

    @Test
    fun `remove from up next drops every copy's row`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p"), testBook("x")), "a", queue = InMemoryUpNext(listOf("p", "x")))
        val state = collect(s)
        s.vm.onRemoveFromUpNextClicked()
        assertEquals(listOf("x"), s.ids.value)
        assertFalse(state().inUpNext)
        assertEquals("Removed from Up next", s.vm.message.value)
    }

    @Test
    fun `the details page shows up next results too`() = runTest {
        val s = Setup(listOf(abs("a")), "a", quick = false)
        collect(s)
        s.vm.onUpNextClicked(front = false)
        assertEquals("Added to Up next", s.vm.message.value)
    }

    @Test
    fun `a failed change says so`() = runTest {
        val broken = object : UpNextRepository {
            override val queue: Flow<List<String>> = MutableStateFlow(emptyList())
            override suspend fun edit(change: (List<String>) -> List<String>): List<String> = throw IllegalStateException("disk")
        }
        val s = Setup(listOf(abs("a")), "a", queue = broken)
        collect(s)
        s.vm.onUpNextClicked(front = true)
        assertEquals("Could not change Up next. Try again.", s.vm.message.value)
    }
}
