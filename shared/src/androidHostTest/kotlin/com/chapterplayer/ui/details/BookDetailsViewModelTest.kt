package com.chapterplayer.ui.details

import com.chapterplayer.data.PlatformFeatures
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeNotificationPermission
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class DetailsRepo(books: List<AudioBook>) : FakeBookRepository(books) {
        var calls = 0
        var next: () -> Result<BookDetails?> = { Result.success(null) }
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun loadDetails(bookId: String): Result<BookDetails?> {
            calls++
            gate?.await()
            return next()
        }
    }

    private fun chapters(vararg titles: String) =
        titles.mapIndexed { i, t -> Chapter(i, t, i * 1_000L, (i + 1) * 1_000L) }

    private fun details(vararg titles: String) = BookDetails(chapters(*titles), "About text", listOf("Fantasy"), "2001", emptyList())

    private fun cachedBook(id: String = "a") =
        testBook(id).copy(chapters = chapters("Old 1", "Old 2"), durationMs = 2_000L)

    private class Setup(
        val repo: DetailsRepo,
        id: String,
        val downloads: FakeDownloadStates = FakeDownloadStates(),
        val controller: FakePlaybackController = FakePlaybackController(),
        val connectivity: FakeConnectivity = FakeConnectivity(),
        val settings: FakeAppSettings = FakeAppSettings(),
        features: PlatformFeatures = PlatformFeatures.Full,
    ) {
        val vm = BookDetailsViewModel(
            id, repo, downloads, controller, PlaybackStarter(controller, repo, settings, connectivity, FakeLocalBookFiles()), connectivity, settings, Dispatchers.Main,
            FakeDownloadOverview(), FakeDownloadController(), FakeNotificationPermission(), features = features
        )
    }

    @Test
    fun `without playback on this platform Play is off with the reason and a tap starts nothing`() = runTest {
        val s = Setup(DetailsRepo(listOf(cachedBook())), "a", features = PlatformFeatures.IosBrowsingOnly)
        val state = collect(s)
        assertFalse(state().canPlay)
        assertEquals("Playback is not available on iOS yet", state().cannotPlayReason)
        s.vm.onResumeClicked()
        assertNull(s.controller.state.value.bookId)
        assertEquals(DetailsPlayback.NOT_PLAYING, state().playback)
    }

    @Test
    fun `without downloads on this platform Download is off with the reason`() = runTest {
        val s = Setup(DetailsRepo(listOf(cachedBook())), "a", features = PlatformFeatures.IosBrowsingOnly)
        val state = collect(s)
        assertEquals(DetailsDownloadPhase.IDLE, state().download.phase)
        assertFalse(state().download.canStart)
        assertEquals("Downloads are not available on iOS yet", state().download.disabledReason)
    }

    @Test
    fun `the streaming iPhone build can play a cached book but not download it`() = runTest {
        val state = collect(Setup(DetailsRepo(listOf(cachedBook())), "a", features = PlatformFeatures.IosStreaming))
        assertTrue(state().canPlay)
        assertNull(state().cannotPlayReason)
        assertFalse(state().download.canStart)
        assertEquals("Downloads are not available on iOS yet", state().download.disabledReason)
    }

    @Test
    fun `with every feature a cached book can play and download`() = runTest {
        val state = collect(Setup(DetailsRepo(listOf(cachedBook())), "a"))
        assertTrue(state().canPlay)
        assertNull(state().cannotPlayReason)
        assertTrue(state().download.canStart)
    }

    private fun TestScope.collect(s: Setup): () -> BookDetailsUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value }
    }

    private fun progress(id: String, pos: Long, finished: Boolean = false) = ReadingProgress(id, pos, 2_000L, 1L, finished)

    @Test
    fun `opens on the Chapters tab and nothing is playing`() = runTest {
        val s = Setup(DetailsRepo(listOf(cachedBook())), "a")
        val state = collect(s)
        assertEquals(DetailsTab.CHAPTERS, state().selectedTab)
        assertEquals(DetailsPlayback.NOT_PLAYING, state().playback)
        assertEquals(DetailsPhase.LOADED, state().phase)
    }

    @Test
    fun `cached chapters show before the live call returns and are replaced after`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.gate = CompletableDeferred()
        repo.next = { Result.success(details("New 1", "New 2", "New 3")) }
        val s = Setup(repo, "a")
        val state = collect(s)

        assertEquals(listOf("Old 1", "Old 2"), state().chapters.map { it.title })
        assertTrue(state().loadingDetails)
        assertTrue(state().about!!.needsConnection)

        repo.gate!!.complete(Unit)

        assertEquals(listOf("New 1", "New 2", "New 3"), state().chapters.map { it.title })
        assertFalse(state().loadingDetails)
        assertFalse(state().about!!.needsConnection)
        assertEquals("About text", state().about!!.description)
        assertNull(state().error)
    }

    @Test
    fun `the current chapter follows the saved progress`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        val s = Setup(repo, "a")
        val state = collect(s)

        assertEquals(listOf(true, false), state().chapters.map { it.isCurrent })

        repo.progress.value = listOf(progress("a", 1_500L))
        assertEquals(listOf(false, true), state().chapters.map { it.isCurrent })

        repo.progress.value = listOf(progress("a", 100L))
        assertEquals(listOf(true, false), state().chapters.map { it.isCurrent })

        repo.progress.value = listOf(progress("a", 2_000L, finished = true))
        assertEquals(listOf(false, true), state().chapters.map { it.isCurrent })
    }

    @Test
    fun `a failing live call keeps the cached chapters and sets an error`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.next = { Result.failure(RuntimeException("boom with http://secret.test")) }
        val state = collect(Setup(repo, "a"))

        assertEquals(listOf("Old 1", "Old 2"), state().chapters.map { it.title })
        assertEquals("Could not load details. Showing what is saved.", state().error)
        assertFalse(state().loadingDetails)
        assertTrue(state().about!!.needsConnection)
    }

    @Test
    fun `retry calls again and clears the error on success`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.next = { Result.failure(RuntimeException("x")) }
        val s = Setup(repo, "a")
        val state = collect(s)
        assertNotNull(state().error)

        repo.next = { Result.success(details("New 1")) }
        s.vm.retry()

        assertEquals(2, repo.calls)
        assertNull(state().error)
        assertEquals(listOf("New 1"), state().chapters.map { it.title })
    }

    @Test
    fun `a later failed or empty retry keeps the live chapters already loaded`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.next = { Result.success(details("New 1")) }
        val s = Setup(repo, "a")
        val state = collect(s)

        repo.next = { Result.failure(RuntimeException("x")) }
        s.vm.retry()
        assertEquals(listOf("New 1"), state().chapters.map { it.title })
        assertNotNull(state().error)

        repo.next = { Result.success(null) }
        s.vm.retry()
        assertEquals(listOf("New 1"), state().chapters.map { it.title })
        assertNull(state().error)
    }

    @Test
    fun `details load once and not again when books, progress or downloads change`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        val downloads = FakeDownloadStates()
        val s = Setup(repo, "a", downloads)
        collect(s)
        assertEquals(1, repo.calls)

        repo.progress.value = listOf(progress("a", 500L))
        downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        repo.books.value = repo.books.value + testBook("b")
        repo.books.value = repo.books.value.map { it.copy(chapters = chapters("Live 1")) }

        assertEquals(1, repo.calls)
    }

    @Test
    fun `retry while the first load is still running does not start a second one`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.gate = CompletableDeferred()
        val s = Setup(repo, "a")
        collect(s)

        s.vm.retry()
        repo.gate!!.complete(Unit)

        assertEquals(1, repo.calls)
    }

    @Test
    fun `retry on an unknown book does not call the server`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        val s = Setup(repo, "zzz")
        collect(s)

        s.vm.retry()

        assertEquals(0, repo.calls)
    }

    @Test
    fun `a position at the end counts as Finished even without the flag`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        val state = collect(Setup(repo, "a"))

        repo.progress.value = listOf(progress("a", 2_000L))

        assertEquals("Finished", state().timeLeft)
    }

    @Test
    fun `a saved position of zero is not started`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.progress.value = listOf(progress("a", 0L))
        val state = collect(Setup(repo, "a"))

        assertNull(state().timeLeft)
    }

    @Test
    fun `a sign-in error is cleared by a later successful retry`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.next = { Result.failure(SignInRequiredException("Server s1")) }
        val s = Setup(repo, "a")
        val state = collect(s)
        assertNotNull(state().error)

        repo.next = { Result.success(details("New 1")) }
        s.vm.retry()

        assertNull(state().error)
    }

    @Test
    fun `finished and not downloaded books still appear in series and links`() = runTest {
        val a = testBook("a", author = "Amy", seriesName = "Saga").copy(seriesSequence = "1")
        val b = testBook("b", author = "Amy", seriesName = "Saga").copy(seriesSequence = "2")
        val repo = DetailsRepo(listOf(a, b))
        repo.progress.value = listOf(progress("b", 2_000L, finished = true))
        val state = collect(Setup(repo, "a"))

        assertEquals(listOf("a", "b"), state().series.map { it.item.book.id })
        assertEquals(1, state().links.authors.single().otherBookCount)
        assertEquals(2, state().links.series!!.bookCount)
    }

    @Test
    fun `a sign-in-required failure says to sign in again`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        repo.next = { Result.failure(SignInRequiredException("Server s1")) }
        val state = collect(Setup(repo, "a"))

        assertEquals("Could not load details. Sign in again in Settings.", state().error)
    }

    @Test
    fun `a null result sets no error and keeps the connection note`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        val state = collect(Setup(repo, "a"))

        assertEquals(1, repo.calls)
        assertNull(state().error)
        assertFalse(state().loadingDetails)
        assertTrue(state().about!!.needsConnection)
        assertFalse(state().detailsLoaded)
        assertEquals(listOf("Old 1", "Old 2"), state().chapters.map { it.title })
    }

    @Test
    fun `details count as loaded only after a successful answer, even with no chapters`() = runTest {
        val repo = DetailsRepo(listOf(testBook("a")))
        repo.next = { Result.failure(java.io.IOException("offline")) }
        val s = Setup(repo, "a")
        val state = collect(s)
        assertFalse(state().detailsLoaded)
        assertTrue(state().chapters.isEmpty())

        repo.next = { Result.success(details()) }
        s.vm.retry()

        assertTrue(state().detailsLoaded)
        assertTrue(state().chapters.isEmpty())
    }

    @Test
    fun `an unknown id is not found and never loads details`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        val state = collect(Setup(repo, "zzz"))

        assertEquals(DetailsPhase.NOT_FOUND, state().phase)
        assertNull(state().book)
        assertEquals(0, repo.calls)
    }

    @Test
    fun `a book removed while open becomes not found`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook()))
        val state = collect(Setup(repo, "a"))
        assertEquals(DetailsPhase.LOADED, state().phase)

        repo.books.value = emptyList()

        assertEquals(DetailsPhase.NOT_FOUND, state().phase)
    }

    @Test
    fun `series lists the same-server series books and marks the current one`() = runTest {
        val a = testBook("a", seriesName = "Saga").copy(seriesSequence = "1")
        val b = testBook("b", seriesName = "Saga").copy(seriesSequence = "2")
        val other = testBook("c", seriesName = "Saga").copy(seriesSequence = "3", serverId = "s2")
        val state = collect(Setup(DetailsRepo(listOf(b, a, other)), "b"))

        assertEquals(listOf("a", "b"), state().series.map { it.item.book.id })
        assertEquals(listOf(false, true), state().series.map { it.isCurrent })
    }

    @Test
    fun `series entries carry download state`() = runTest {
        val repo = DetailsRepo(listOf(testBook("a", seriesName = "Saga"), testBook("b", seriesName = "Saga")))
        val downloads = FakeDownloadStates(mapOf("a" to DownloadState.DOWNLOADED))
        val s = Setup(repo, "a", downloads)
        collect(s)

        assertEquals(DownloadState.DOWNLOADED, s.vm.uiState.value.series.first { it.item.book.id == "a" }.item.downloadState)
    }

    @Test
    fun `links has the author with the number of other books`() = runTest {
        val repo = DetailsRepo(listOf(testBook("a", author = "Amy"), testBook("b", author = "Amy"), testBook("c", author = "Zed")))
        val state = collect(Setup(repo, "a"))

        val authors = state().links.authors
        assertEquals(listOf("Amy"), authors.map { it.title })
        assertEquals(1, authors.single().otherBookCount)
    }

    @Test
    fun `selecting a tab changes it and survives a new collection`() = runTest {
        val s = Setup(DetailsRepo(listOf(cachedBook())), "a")
        val first = collect(s)
        s.vm.selectTab(DetailsTab.ABOUT)
        assertEquals(DetailsTab.ABOUT, first().selectedTab)

        val second = collect(s)
        assertEquals(DetailsTab.ABOUT, second().selectedTab)
    }

    @Test
    fun `time left shows for a started book, Finished for a finished one and nothing before`() = runTest {
        val repo = DetailsRepo(listOf(cachedBook().copy(durationMs = 3_600_000L)))
        val state = collect(Setup(repo, "a"))
        assertNull(state().timeLeft)
        assertEquals("1 h", state().lengthText)

        repo.progress.value = listOf(ReadingProgress("a", 1_800_000L, 3_600_000L, 1L))
        assertEquals("30 min left", state().timeLeft)

        repo.progress.value = listOf(ReadingProgress("a", 3_600_000L, 3_600_000L, 1L, isFinished = true))
        assertEquals("Finished", state().timeLeft)
    }

    // ---- playback ----

    private fun longBook(id: String = "a") = cachedBook(id).copy(durationMs = 100_000L)

    private fun fresh(book: AudioBook = longBook(), id: String = book.id) = Setup(DetailsRepo(listOf(book)), id)

    private fun loadedState(bookId: String = "a", playing: Boolean = false, positionMs: Long = 0L, durationMs: Long = 100_000L) =
        PlaybackState(bookId = bookId, isPlaying = playing, positionMs = positionMs, durationMs = durationMs)

    @Test
    fun `resume starts at the saved position and plays`() = runTest {
        val s = fresh()
        s.repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L))
        val state = collect(s)
        assertEquals(40_000L, state().resumePositionMs)

        s.vm.onResumeClicked()

        assertEquals(listOf("a" to 40_000L), s.controller.loads)
        assertEquals(1, s.controller.plays)
        assertEquals(DetailsPlayback.PLAYING, state().playback)
    }

    @Test
    fun `no progress asks for the server position and a finished book starts at zero`() = runTest {
        val s = fresh()
        val state = collect(s)
        s.vm.onResumeClicked()
        assertEquals(listOf("a" to -1L), s.controller.loads)

        val finished = fresh()
        finished.repo.progress.value = listOf(ReadingProgress("a", 99_000L, 100_000L, 1L, isFinished = true))
        collect(finished)
        finished.vm.onResumeClicked()
        assertEquals(listOf("a" to 0L), finished.controller.loads)
        assertEquals(DetailsPlayback.PLAYING, state().playback)
    }

    @Test
    fun `starting blocks a second click`() = runTest {
        val s = fresh()
        val gate = CompletableDeferred<Unit>()
        s.controller.onLoad = { _, _ -> gate.await() }
        val state = collect(s)

        s.vm.onResumeClicked()
        assertEquals(DetailsPlayback.STARTING, state().playback)
        s.vm.onResumeClicked()
        s.vm.onChapterClicked(1)
        gate.complete(Unit)

        assertEquals(1, s.controller.loads.size)
        assertEquals(1, s.controller.plays)
    }

    @Test
    fun `a playback start failure shows its message and returns to not playing`() = runTest {
        val s = fresh()
        s.controller.onLoad = { _, _ -> throw PlaybackStartException("Sign in again in Settings") }
        val state = collect(s)

        s.vm.onResumeClicked()

        assertEquals("Sign in again in Settings", state().playError)
        assertEquals(DetailsPlayback.NOT_PLAYING, state().playback)
        assertEquals(0, s.controller.plays)
    }

    @Test
    fun `an unexpected failure shows the generic text without its own message`() = runTest {
        val s = fresh()
        s.controller.onLoad = { _, _ -> throw IllegalStateException("secret http://host token=abc") }
        val state = collect(s)

        s.vm.onResumeClicked()

        assertEquals("Could not start playback", state().playError)
        assertEquals(DetailsPlayback.NOT_PLAYING, state().playback)
    }

    @Test
    fun `a retry after a failure clears the error and starts again`() = runTest {
        val s = fresh()
        s.controller.onLoad = { _, _ -> throw PlaybackStartException("No luck") }
        val state = collect(s)
        s.vm.onResumeClicked()
        assertNotNull(state().playError)

        s.controller.onLoad = { book, _ -> s.controller.playback.value = loadedState(book.id) }
        s.vm.onResumeClicked()

        assertNull(state().playError)
        assertEquals(DetailsPlayback.PLAYING, state().playback)
    }

    @Test
    fun `dismissing the error clears it`() = runTest {
        val s = fresh()
        s.controller.onLoad = { _, _ -> throw PlaybackStartException("No luck") }
        val state = collect(s)
        s.vm.onResumeClicked()

        s.vm.dismissPlayError()

        assertNull(state().playError)
    }

    @Test
    fun `cancellation is not turned into an error`() = runTest {
        val s = fresh()
        s.controller.onLoad = { _, _ -> throw kotlinx.coroutines.CancellationException("cancelled") }
        val state = collect(s)

        s.vm.onResumeClicked()

        assertNull(state().playError)
        assertEquals(DetailsPlayback.NOT_PLAYING, state().playback)
        assertEquals(0, s.controller.plays)
    }

    @Test
    fun `clicking while playing pauses and clicking while paused plays`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(playing = true)
        val state = collect(s)
        assertEquals(DetailsPlayback.PLAYING, state().playback)

        s.vm.onResumeClicked()
        assertEquals(1, s.controller.pauses)
        assertEquals(DetailsPlayback.PAUSED, state().playback)

        s.vm.onResumeClicked()
        assertEquals(1, s.controller.plays)
        assertEquals(DetailsPlayback.PLAYING, state().playback)
        assertTrue(s.controller.loads.isEmpty())
    }

    @Test
    fun `another book playing is replaced and does not count as this book`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(bookId = "other", playing = true)
        val state = collect(s)
        assertEquals(DetailsPlayback.NOT_PLAYING, state().playback)

        s.vm.onResumeClicked()

        assertEquals(listOf("a" to -1L), s.controller.loads)
        assertEquals(0, s.controller.pauses)
        assertEquals(DetailsPlayback.PLAYING, state().playback)
    }

    @Test
    fun `offline and not downloaded cannot play and says why`() = runTest {
        val s = fresh()
        s.connectivity.online.value = false
        val state = collect(s)

        assertFalse(state().canPlay)
        assertEquals("Needs a connection to play", state().cannotPlayReason)
        s.vm.onResumeClicked()
        assertTrue(s.controller.loads.isEmpty())
    }

    @Test
    fun `offline but downloaded can play`() = runTest {
        val s = fresh()
        s.connectivity.online.value = false
        s.downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        val state = collect(s)

        assertTrue(state().canPlay)
        assertNull(state().cannotPlayReason)
    }

    @Test
    fun `downloaded only blocks a book that is not downloaded`() = runTest {
        val s = fresh()
        s.settings.downloadedOnlyFlow.value = true
        val state = collect(s)

        assertFalse(state().canPlay)
        assertEquals("Needs a connection to play", state().cannotPlayReason)

        s.downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        assertTrue(state().canPlay)
    }

    @Test
    fun `online with a book that is not downloaded can play`() = runTest {
        val state = collect(fresh())

        assertTrue(state().canPlay)
        assertNull(state().cannotPlayReason)
    }

    @Test
    fun `pausing works even when the connection is gone`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(playing = true)
        s.connectivity.online.value = false
        collect(s)

        s.vm.onResumeClicked()

        assertEquals(1, s.controller.pauses)
    }

    @Test
    fun `a chapter click while the book is loaded seeks to that chapter`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(playing = true)
        collect(s)

        s.vm.onChapterClicked(1)

        assertEquals(listOf(1_000L), s.controller.seeks)
        assertTrue(s.controller.loads.isEmpty())
        assertEquals(0, s.controller.pauses)
        assertEquals(0, s.controller.plays)
    }

    @Test
    fun `a chapter click while paused seeks and keeps it paused`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(playing = false)
        val state = collect(s)

        s.vm.onChapterClicked(0)

        assertEquals(listOf(0L), s.controller.seeks)
        assertEquals(DetailsPlayback.PAUSED, state().playback)
    }

    @Test
    fun `a chapter click with nothing playing loads at the chapter start and plays`() = runTest {
        val s = fresh()
        val state = collect(s)

        s.vm.onChapterClicked(1)

        assertEquals(listOf("a" to 1_000L), s.controller.loads)
        assertEquals(1, s.controller.plays)
        assertTrue(s.controller.seeks.isEmpty())
        assertEquals(DetailsPlayback.PLAYING, state().playback)
    }

    @Test
    fun `a Plex book plays online and takes chapter taps like any other`() = runTest {
        val s = fresh(longBook().copy(id = "plex_a", source = ServerSource.PLEX))
        val state = collect(s)

        assertTrue(state().canPlay)
        assertNull(state().cannotPlayReason)
        assertTrue(chapterTapsEnabled(state().playback, state().canPlay))
        s.vm.onChapterClicked(1)
        assertEquals(listOf("plex_a" to 1_000L), s.controller.loads)
        assertEquals(1, s.controller.plays)
    }

    @Test
    fun `offline a Plex book needs a connection`() = runTest {
        val s = fresh(longBook().copy(id = "plex_a", source = ServerSource.PLEX))
        s.connectivity.online.value = false
        val state = collect(s)

        assertFalse(state().canPlay)
        assertEquals("Needs a connection to play", state().cannotPlayReason)
    }

    @Test
    fun `a chapter click is ignored when the book cannot play or the index is unknown`() = runTest {
        val s = fresh()
        s.connectivity.online.value = false
        collect(s)

        s.vm.onChapterClicked(1)
        assertTrue(s.controller.loads.isEmpty())

        s.connectivity.online.value = true
        s.vm.onChapterClicked(9)
        s.vm.onChapterClicked(-1)
        assertTrue(s.controller.loads.isEmpty())
    }

    @Test
    fun `the highlight follows the live position of this book`() = runTest {
        val s = fresh()
        s.repo.progress.value = listOf(ReadingProgress("a", 100L, 100_000L, 1L))
        s.controller.playback.value = loadedState(positionMs = 1_500L)
        val state = collect(s)

        assertEquals(listOf(false, true), state().chapters.map { it.isCurrent })
        assertEquals(1_500L, state().currentPositionMs)

        s.controller.playback.value = loadedState(positionMs = 200L)
        assertEquals(listOf(true, false), state().chapters.map { it.isCurrent })
    }

    @Test
    fun `the highlight ignores another book that is playing`() = runTest {
        val s = fresh()
        s.repo.progress.value = listOf(ReadingProgress("a", 100L, 100_000L, 1L))
        s.controller.playback.value = loadedState(bookId = "other", playing = true, positionMs = 1_500L)
        val state = collect(s)

        assertEquals(listOf(true, false), state().chapters.map { it.isCurrent })
        assertEquals(100L, state().currentPositionMs)
    }

    @Test
    fun `buffering after play still offers Pause, buffering without a wish to play does not`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState()
        val state = collect(s)
        assertEquals(DetailsPlayback.PAUSED, state().playback)

        s.controller.play() // the real player buffers before the first sound
        assertEquals(DetailsPlayback.PLAYING, state().playback)
        s.controller.soundStarts()
        assertEquals(DetailsPlayback.PLAYING, state().playback)

        s.controller.playback.value = loadedState().copy(isBuffering = true, playWhenReady = false)
        assertEquals(DetailsPlayback.PAUSED, state().playback)
    }

    @Test
    fun `a failure while playing shows its text, Dismiss hides it and trying again clears it`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState().copy(error = "Sign in again in Settings")
        val state = collect(s)
        assertEquals("Sign in again in Settings", state().playError)

        s.vm.dismissPlayError()
        assertNull(state().playError)

        s.controller.playback.value = loadedState().copy(error = "The audio was not found on the server")
        assertEquals("The audio was not found on the server", state().playError)

        s.vm.onResumeClicked() // Retry plays again
        assertEquals(1, s.controller.plays)
    }

    @Test
    fun `another book's failure is not shown here and a start failure wins`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(bookId = "other").copy(error = "Playback failed")
        val state = collect(s)
        assertNull(state().playError)

        s.controller.playback.value = loadedState().copy(error = "Playback failed")
        assertEquals("Playback failed", state().playError)
    }

    @Test
    fun `resume on a book that played to its end starts again at the beginning`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(positionMs = 99_000L)
        collect(s)

        s.vm.onResumeClicked()

        assertEquals(listOf(0L), s.controller.seeks)
        assertEquals(1, s.controller.plays)
    }

    @Test
    fun `resume in the middle of a loaded book does not seek`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState(positionMs = 50_000L)
        collect(s)

        s.vm.onResumeClicked()

        assertTrue(s.controller.seeks.isEmpty())
    }

    @Test
    fun `the same player error shows again after it went away and came back`() = runTest {
        val s = fresh()
        s.controller.playback.value = loadedState().copy(error = "Playback failed")
        val state = collect(s)
        s.vm.dismissPlayError()
        assertNull(state().playError)

        s.controller.playback.value = loadedState() // fixed by playing again
        s.controller.playback.value = loadedState().copy(error = "Playback failed")

        assertEquals("Playback failed", state().playError)
    }
}
