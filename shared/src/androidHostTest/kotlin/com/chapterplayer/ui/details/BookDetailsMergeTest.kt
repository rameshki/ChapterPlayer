package com.chapterplayer.ui.details

import com.chapterplayer.data.merge.BookMergeRepository
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.downloadRow
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.SourcePreference
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
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
class BookDetailsMergeTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun abs(id: String, serverId: String = "abs1", title: String = "Same Book") =
        testBook(id, title = title, author = "Same Author").copy(serverId = serverId, durationMs = 3_600_000L)
    private fun plex(id: String) = abs(id, "plex1").copy(source = ServerSource.PLEX)

    private class FakeMerges : BookMergeRepository {
        val flow = MutableStateFlow(MergeOverrides())
        val calls = mutableListOf<String>()
        override val overrides: Flow<MergeOverrides> = flow
        override suspend fun split(bookId: String, otherIds: Collection<String>) { calls += "split $bookId ${otherIds.sorted()}" }
        override suspend fun merge(bookId: String, otherId: String, group: Collection<String>, otherGroup: Collection<String>) {
            calls += "merge $bookId $otherId ${group.sorted()} ${otherGroup.sorted()}"
            if (joins) flow.value = flow.value.copy(merges = flow.value.merges + (bookId to otherId))
        }
        /** Whether a merge is stored (the join then shows); false acts as a join that cannot happen. */
        var joins = true
        override suspend fun forgetBooks(bookIds: Collection<String>) {}
    }

    private class Setup(
        books: List<AudioBook>,
        id: String,
        preference: SourcePreference = SourcePreference.PREFER_DOWNLOADED,
        online: Set<String> = setOf("abs1", "abs2", "plex1"),
        downloadStates: Map<String, DownloadState> = emptyMap(),
        deviceOnline: Boolean = true,
        /** What each copy's server says a download fetches. */
        sizes: Map<String, Long> = emptyMap(),
        /** The download rows on the phone, by book id. */
        rows: Map<String, com.chapterplayer.data.downloads.DownloadRow> = emptyMap(),
    ) {
        val repo = object : FakeBookRepository(books) {
            override suspend fun loadDetails(bookId: String): Result<BookDetails?> =
                Result.success(sizes[bookId]?.let { BookDetails(emptyList(), null, emptyList(), null, emptyList(), downloadSizeBytes = it) })
        }
        val controller = FakePlaybackController()
        val settings = FakeAppSettings()
        val connectivity = FakeConnectivity()
        val downloads = FakeDownloadController()
        val merges = FakeMerges()
        val servers = FakeServerList(listOf(
            ServerConfig("abs1", ServerSource.ABS, "Home ABS", "http://a", "t"),
            ServerConfig("abs2", ServerSource.ABS, "Work ABS", "http://b", "t"),
            ServerConfig("plex1", ServerSource.PLEX, "Home Plex", "http://p", "t"),
        ))
        val vm = BookDetailsViewModel(
            id, repo, FakeDownloadStates(downloadStates), controller,
            PlaybackStarter(controller, repo, settings, connectivity, FakeLocalBookFiles()), connectivity, settings, Dispatchers.Main,
            FakeDownloadOverview(rows.values.toList()), downloads, FakeNotificationPermission(),
            // The corrections flow into the merging, as AppSourceMerging does.
            merging = object : com.chapterplayer.data.merge.SourceMerging {
                override val context = merges.flow.map { MergeContext(it, preference, online, deviceOnline) }
            },
            merges = merges,
            servers = servers,
        )
    }

    private fun TestScope.collect(s: Setup): () -> BookDetailsUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value }
    }

    @Test
    fun `a merged book lists its sources with labels and server names`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a")
        val state = collect(s)
        assertEquals(listOf("a", "p"), state().sources.map { it.bookId })
        assertEquals(listOf("ABS", "Plex"), state().sources.map { it.typeLabel })
        assertEquals(listOf("Home ABS", "Home Plex"), state().sources.map { it.serverName })
        assertEquals("available from Audiobookshelf and Plex", state().sourcesDescription)
        assertTrue(state().canSplit)
        assertTrue(state().canMerge)
    }

    @Test
    fun `the other copy's page opens too`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "p")
        val state = collect(s)
        assertEquals(DetailsPhase.LOADED, state().phase)
        assertEquals("p", state().book?.id)
    }

    @Test
    fun `a book on one server has no sources section and plays at once`() = runTest {
        val s = Setup(listOf(abs("a"), abs("x", title = "Other")), "a")
        val state = collect(s)
        assertTrue(state().sources.isEmpty())
        assertFalse(state().canSplit)
        s.vm.onResumeClicked()
        assertNull(state().sourceSheet)
        assertEquals("a", s.controller.loads.single().first)
    }

    @Test
    fun `the preference decides which copy plays`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", preference = SourcePreference.PREFER_PLEX)
        val state = collect(s)
        s.vm.onResumeClicked()
        assertNull(state().sourceSheet)
        assertEquals("p", s.controller.loads.single().first)
    }

    @Test
    fun `always ask opens the sheet and the chosen copy plays`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", preference = SourcePreference.ASK)
        val state = collect(s)
        s.vm.onResumeClicked()
        val sheet = assertNotNull(state().sourceSheet)
        assertEquals(SourceAction.PLAY, sheet.action)
        assertTrue(s.controller.loads.isEmpty())
        s.vm.onSourceChosen("p")
        assertNull(state().sourceSheet)
        assertEquals("p", s.controller.loads.single().first)
    }

    @Test
    fun `the sheet greys an unreachable copy and will not play it`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", preference = SourcePreference.ASK, online = setOf("abs1"))
        val state = collect(s)
        s.vm.onResumeClicked()
        val plexRow = state().sourceSheet!!.rows.first { it.bookId == "p" }
        assertFalse(plexRow.enabled)
        assertEquals("Server not reachable", plexRow.reason)
        s.vm.onSourceChosen("p")
        assertNotNull(state().sourceSheet)
        assertTrue(s.controller.loads.isEmpty())
    }

    @Test
    fun `downloaded Audiobookshelf offline plays from the phone, not Plex`() = runTest {
        val s = Setup(
            listOf(abs("a"), plex("p")), "p",
            preference = SourcePreference.PREFER_DOWNLOADED, online = emptySet(),
            downloadStates = mapOf("a" to DownloadState.DOWNLOADED), deviceOnline = false
        )
        s.connectivity.online.value = false
        val state = collect(s)
        assertTrue(state().canPlay)
        assertEquals(listOf("a", "p"), state().sources.map { it.bookId })
        assertTrue(state().sources.first().downloaded)
        assertEquals("No network", state().sources.last().reason)
    }

    @Test
    fun `two Audiobookshelf copies ask where to download from when the choice is ask`() = runTest {
        val s = Setup(listOf(abs("a"), abs("b", "abs2")), "a", preference = SourcePreference.ASK)
        val state = collect(s)
        s.vm.onDownloadClicked()
        assertEquals(SourceAction.DOWNLOAD, state().sourceSheet?.action)
        s.vm.onSourceChosen("b")
        assertEquals(listOf("enqueue b"), s.downloads.calls)
    }

    @Test
    fun `prefer downloaded with nothing downloaded takes the reachable Audiobookshelf copy, also from the Plex page`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "p")
        val state = collect(s)
        assertEquals("p", state().downloadBookId)
        assertEquals(DetailsDownloadPhase.IDLE, state().download.phase)
        assertEquals("Download", state().download.buttonLabel)
        assertNull(state().download.note)
        s.vm.onDownloadClicked()
        assertEquals(listOf("enqueue a"), s.downloads.calls)
    }

    @Test
    fun `prefer downloaded takes the Plex copy when only Plex answers`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", online = setOf("plex1"))
        collect(s)
        s.vm.onDownloadClicked()
        assertEquals(listOf("enqueue p"), s.downloads.calls)
    }

    @Test
    fun `prefer Plex downloads the Plex copy`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", preference = SourcePreference.PREFER_PLEX)
        val state = collect(s)
        s.vm.onDownloadClicked()
        assertNull(state().sourceSheet)
        assertEquals(listOf("enqueue p"), s.downloads.calls)
    }

    @Test
    fun `always ask offers both copies to download from, with the sizes their servers give`() = runTest {
        val s = Setup(
            listOf(abs("a"), plex("p")), "a", preference = SourcePreference.ASK,
            sizes = mapOf("a" to 1_240_000_000L, "p" to 980_000_000L),
        )
        val state = collect(s)
        s.vm.onDownloadClicked()
        val sheet = assertNotNull(state().sourceSheet)
        assertEquals(SourceAction.DOWNLOAD, sheet.action)
        assertEquals("Download from", sheet.title)
        assertEquals(listOf("a", "p"), sheet.rows.map { it.bookId })
        assertEquals(listOf("1.2 GB", "980 MB"), sheet.rows.map { it.sizeText })
        assertTrue(sheet.rows.all { it.enabled })
        s.vm.onSourceChosen("p")
        assertNull(state().sourceSheet)
        assertEquals(listOf("enqueue p"), s.downloads.calls)
    }

    @Test
    fun `the download sheet greys a copy whose server does not answer`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", preference = SourcePreference.ASK, online = setOf("abs1"))
        val state = collect(s)
        s.vm.onDownloadClicked()
        val plexRow = state().sourceSheet!!.rows.single { it.bookId == "p" }
        assertFalse(plexRow.enabled)
        assertEquals("Server not reachable", plexRow.reason)
        s.vm.onSourceChosen("p")
        assertTrue(s.downloads.calls.isEmpty())
    }

    @Test
    fun `a copy already downloaded is what the control shows, so nothing more is offered`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", rows = mapOf("p" to downloadRow("p", DownloadStatus.DONE, totalBytes = 5_000_000)))
        val state = collect(s)
        assertEquals("p", state().downloadBookId)
        assertEquals(DetailsDownloadPhase.DONE, state().download.phase)
        s.vm.onDownloadClicked()
        assertTrue(s.downloads.calls.isEmpty())
    }

    @Test
    fun `not the same book splits this copy from the others`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "p")
        collect(s)
        s.vm.onNotSameBookClicked()
        assertEquals(listOf("split p [a]"), s.merges.calls)
    }

    @Test
    fun `merge with searches the other servers' books and merges the chosen one`() = runTest {
        val s = Setup(listOf(abs("a"), plex("q").copy(title = "Something else"), abs("x", title = "Other")), "a")
        val state = collect(s)
        s.vm.onMergeWithClicked()
        assertEquals(listOf("q"), state().mergeSheet?.candidates?.map { it.bookId })
        s.vm.onMergeQueryChanged("nothing like it")
        assertEquals(emptyList(), state().mergeSheet?.candidates)
        s.vm.onMergeQueryChanged("something")
        s.vm.onMergeCandidateChosen("q")
        testScheduler.advanceUntilIdle()
        assertNull(state().mergeSheet)
        assertEquals(listOf("merge a q [a] [q]"), s.merges.calls)
        assertEquals(listOf("a", "q"), state().sources.map { it.bookId })
        assertNull(state().notice)
    }

    @Test
    fun `a merge that cannot show as one says so`() = runTest {
        val s = Setup(listOf(abs("a"), plex("q").copy(title = "Something else")), "a")
        s.merges.joins = false
        val state = collect(s)
        s.vm.onMergeWithClicked()
        s.vm.onMergeCandidateChosen("q")
        testScheduler.advanceUntilIdle()
        assertEquals("These books could not be shown as one", state().notice)
        s.vm.onNoticeDismissed()
        assertNull(state().notice)
    }

    @Test
    fun `with no copy able to play, Play says so instead of opening the sheet`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p")), "a", preference = SourcePreference.ASK, online = emptySet())
        val state = collect(s)
        s.vm.onResumeClicked()
        assertNull(state().sourceSheet)
        assertEquals("No copy of this book can play now (Server not reachable)", state().playError)
        assertTrue(s.controller.loads.isEmpty())
    }

    @Test
    fun `another copy in the player shows where it plays from and keeps chapter taps off`() = runTest {
        val s = Setup(listOf(abs("a").copy(chapters = listOf(com.chapterplayer.data.model.Chapter(0, "One", 0L, 1_000L))), plex("p")), "a")
        val state = collect(s)
        s.controller.playback.value = com.chapterplayer.data.playback.PlaybackState(bookId = "p", positionMs = 999_000L, durationMs = 3_600_000L)
        assertEquals(DetailsPlayback.PAUSED, state().playback)
        assertEquals("Playing from Plex · Home Plex", state().playingFrom)
        assertEquals(0L, state().currentPositionMs)
        s.vm.onChapterClicked(0)
        assertTrue(s.controller.seeks.isEmpty())
    }

    @Test
    fun `a chapter tap with nothing playing starts the copy the preference picks`() = runTest {
        val s = Setup(
            listOf(abs("a").copy(chapters = listOf(com.chapterplayer.data.model.Chapter(0, "One", 0L, 1_000L), com.chapterplayer.data.model.Chapter(1, "Two", 1_000L, 2_000L))), plex("p")),
            "a", preference = SourcePreference.PREFER_PLEX
        )
        val state = collect(s)
        s.vm.onChapterClicked(1)
        assertEquals("p" to 1_000L, s.controller.loads.single())
        assertNull(state().sourceSheet)
    }
    @Test
    fun `a Plex copy without a length shows the length of the other copy`() = runTest {
        val s = Setup(listOf(abs("a"), plex("p").copy(durationMs = 0L)), "p")
        val state = collect(s)
        assertEquals(listOf("a", "p"), state().sources.map { it.bookId })
        assertEquals(com.chapterplayer.domain.browse.formatLength(3_600_000L), state().lengthText)
    }
}
