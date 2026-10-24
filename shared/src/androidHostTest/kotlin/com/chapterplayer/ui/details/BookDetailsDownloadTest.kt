package com.chapterplayer.ui.details

import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeNotificationPermission
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
import kotlin.test.assertTrue

/** The download control on Book details: each state, and what each button asks the controller. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsDownloadTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Repo(size: Long?, plex: Boolean) : FakeBookRepository(
        listOf(testBook("a").let { if (plex) it.copy(source = ServerSource.PLEX) else it })
    ) {
        val details = BookDetails(emptyList(), null, emptyList(), null, emptyList(), downloadSizeBytes = size)
        override suspend fun loadDetails(bookId: String): Result<BookDetails?> = Result.success(details)
    }

    private class Setup(size: Long? = null, plex: Boolean = false, online: Boolean = true) {
        val repo = Repo(size, plex)
        val overview = FakeDownloadOverview()
        val downloads = FakeDownloadController()
        val permission = FakeNotificationPermission(log = downloads.calls)
        val connectivity = FakeConnectivity(online)
        val settings = FakeAppSettings()
        val player = FakePlaybackController()
        val vm = BookDetailsViewModel(
            "a", repo, FakeDownloadStates(), player, PlaybackStarter(player, repo, settings, connectivity), connectivity, settings,
            Dispatchers.Main, overview, downloads, permission
        )
        fun status(status: DownloadStatus, fraction: Float = 0f, eta: Long? = null, error: String? = null) {
            overview.rows.value = listOf(downloadRow("a", status, fraction = fraction, etaSeconds = eta, error = error, totalBytes = 500_000_000))
        }
    }

    private fun TestScope.collect(s: Setup): () -> DetailsDownload {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value.download }
    }

    @Test
    fun `idle shows Download with the size from the server`() = runTest {
        val s = Setup(size = 1_240_000_000)
        val download = collect(s)
        assertEquals(DetailsDownloadPhase.IDLE, download().phase)
        assertEquals("Download (1.2 GB)", download().buttonLabel)
    }

    @Test
    fun `Download queues the book and asks for notifications`() = runTest {
        val s = Setup()
        collect(s)

        s.vm.onDownloadClicked()

        assertEquals(listOf("notifications=true", "enqueue a"), s.downloads.calls)
        assertEquals(1, s.permission.asks)
    }

    @Test
    fun `a refused notification permission still downloads`() = runTest {
        val s = Setup()
        s.permission.granted = false
        collect(s)

        s.vm.onDownloadClicked()

        // The refusal came back first, and the book was queued after it all the same.
        assertEquals(listOf("notifications=false", "enqueue a"), s.downloads.calls)
    }

    @Test
    fun `offline the Download button does nothing`() = runTest {
        val s = Setup(online = false)
        val download = collect(s)
        assertFalse(download().canStart)
        assertEquals("Needs a connection to download", download().disabledReason)

        s.vm.onDownloadClicked()

        assertTrue(s.downloads.calls.isEmpty())
    }

    @Test
    fun `queued, downloading and paused follow the table`() = runTest {
        val s = Setup()
        val download = collect(s)

        s.status(DownloadStatus.QUEUED)
        assertEquals(DetailsDownloadPhase.QUEUED, download().phase)

        s.status(DownloadStatus.DOWNLOADING, fraction = 0.5f, eta = 600)
        assertEquals(DetailsDownloadPhase.DOWNLOADING, download().phase)
        assertEquals("50% · 10m left", download().statusText)

        s.status(DownloadStatus.PAUSED, fraction = 0.5f)
        assertEquals(DetailsDownloadPhase.PAUSED, download().phase)
        assertEquals("Paused · 50%", download().statusText)
    }

    @Test
    fun `pause, resume and cancel go to the controller`() = runTest {
        val s = Setup()
        collect(s)
        s.status(DownloadStatus.DOWNLOADING)
        s.vm.onPauseDownloadClicked()
        s.status(DownloadStatus.PAUSED)
        s.vm.onResumeDownloadClicked()
        s.vm.onCancelDownloadClicked()

        assertEquals(listOf("pause a", "resume a", "cancel a"), s.downloads.calls)
    }

    @Test
    fun `failed shows the stored error and Try again resumes`() = runTest {
        val s = Setup()
        val download = collect(s)
        s.status(DownloadStatus.FAILED, error = "Not enough space on this phone")
        assertEquals("Not enough space on this phone", download().statusText)

        s.vm.onDownloadClicked()

        assertEquals(listOf("notifications=true", "resume a"), s.downloads.calls)
    }

    @Test
    fun `remove asks first and only the confirmation removes`() = runTest {
        val s = Setup()
        val download = collect(s)
        s.status(DownloadStatus.DONE, fraction = 1f)
        assertEquals(DetailsDownloadPhase.DONE, download().phase)
        assertEquals("Downloaded · 500 MB", download().statusText)

        s.vm.onRemoveDownloadClicked()
        assertTrue(download().confirmingRemove)
        assertTrue(s.downloads.calls.isEmpty())

        s.vm.onRemoveDownloadDismissed()
        assertFalse(download().confirmingRemove)
        assertTrue(s.downloads.calls.isEmpty())

        s.vm.onRemoveDownloadClicked()
        s.vm.onRemoveDownloadConfirmed()
        assertFalse(download().confirmingRemove)
        assertEquals(listOf("remove a"), s.downloads.calls)
    }

    @Test
    fun `offline a download already on its way can still be paused, resumed, cancelled or removed`() = runTest {
        val s = Setup(online = false)
        val download = collect(s)
        s.status(DownloadStatus.PAUSED, fraction = 0.3f)
        assertEquals(DetailsDownloadPhase.PAUSED, download().phase)
        s.vm.onResumeDownloadClicked()
        s.vm.onCancelDownloadClicked()
        s.status(DownloadStatus.DONE, fraction = 1f)
        s.vm.onRemoveDownloadClicked()
        s.vm.onRemoveDownloadConfirmed()

        assertEquals(listOf("resume a", "cancel a", "remove a"), s.downloads.calls)
    }

    @Test
    fun `buttons that do not fit the state do nothing`() = runTest {
        val s = Setup()
        collect(s)
        s.vm.onPauseDownloadClicked()
        s.vm.onResumeDownloadClicked()
        s.vm.onCancelDownloadClicked()
        s.vm.onRemoveDownloadClicked()
        s.vm.onRemoveDownloadConfirmed()
        s.status(DownloadStatus.DONE, fraction = 1f)
        s.vm.onDownloadClicked()
        s.vm.onPauseDownloadClicked()

        assertTrue(s.downloads.calls.isEmpty())
    }

    @Test
    fun `a failing controller call does not crash the screen`() = runTest {
        val s = Setup()
        val download = collect(s)
        s.downloads.failWith = IllegalStateException("disk")

        s.vm.onDownloadClicked()

        // The call was made, failed, and the control still shows Download and still works.
        assertEquals(listOf("notifications=true", "enqueue a"), s.downloads.calls)
        assertEquals(DetailsDownloadPhase.IDLE, download().phase)
        assertTrue(download().canStart)
        s.downloads.failWith = null
        s.vm.onDownloadClicked()
        assertEquals(2, s.downloads.calls.count { it == "enqueue a" })
    }

    @Test
    fun `a plex book has no download control`() = runTest {
        val s = Setup(plex = true)
        val download = collect(s)
        assertEquals(DetailsDownloadPhase.HIDDEN, download().phase)

        s.vm.onDownloadClicked()

        assertTrue(s.downloads.calls.isEmpty())
    }
}
