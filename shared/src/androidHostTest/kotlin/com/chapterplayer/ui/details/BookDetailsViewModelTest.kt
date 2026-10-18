package com.chapterplayer.ui.details

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
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

    private class Setup(val repo: DetailsRepo, id: String) {
        val vm = BookDetailsViewModel(id, repo, FakeDownloadStates(), Dispatchers.Main)
    }

    private fun TestScope.collect(s: Setup): () -> BookDetailsUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value }
    }

    private fun progress(id: String, pos: Long, finished: Boolean = false) = ReadingProgress(id, pos, 2_000L, 1L, finished)

    @Test
    fun `opens on the Chapters tab and playback is not enabled yet`() = runTest {
        val s = Setup(DetailsRepo(listOf(cachedBook())), "a")
        val state = collect(s)
        assertEquals(DetailsTab.CHAPTERS, state().selectedTab)
        assertFalse(state().resumeEnabled)
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
        assertEquals(listOf("Old 1", "Old 2"), state().chapters.map { it.title })
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
        val s = BookDetailsViewModel("a", repo, downloads, Dispatchers.Main)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.uiState.collect {} }

        assertEquals(DownloadState.DOWNLOADED, s.uiState.value.series.first { it.item.book.id == "a" }.item.downloadState)
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
}
