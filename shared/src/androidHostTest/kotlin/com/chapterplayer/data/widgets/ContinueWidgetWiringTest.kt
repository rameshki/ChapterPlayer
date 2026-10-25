package com.chapterplayer.data.widgets

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWidgetWiringTest {

    private val controller = FakePlaybackController()
    private val repo = FakeBookRepository(
        listOf(
            testBook("a", title = "Alpha", author = "Ann").copy(durationMs = 100_000L),
            testBook("b", title = "Beta", author = "Bo").copy(durationMs = 100_000L),
        )
    )
    private val downloads = FakeDownloadStates()
    private val connectivity = FakeConnectivity()
    private val settings = FakeAppSettings()
    private val states = WidgetStates(controller, repo, downloads, connectivity, settings)
    private val openPlayer = OpenPlayerRequest()
    private val starter = PlaybackStarter(controller, repo, settings, connectivity, FakeLocalBookFiles())
    private val resumer = WidgetResumer(states, repo, starter, openPlayer)

    @Test
    fun theStateFollowsProgressAndTheNetwork() = runTest {
        assertEquals(ContinueWidgetState.Empty, states.continueListening.first())
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        assertEquals(ContinueWidgetState.Book("a", "Alpha", "Ann", 25, "1 min left"), states.continueListening.first())

        connectivity.online.value = false
        assertEquals(ContinueWidgetState.Empty, states.continueListening.first())
        downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED)
        assertIs<ContinueWidgetState.Book>(states.continueListening.first())
    }

    @Test
    fun aBurstOfChangesIsOneRedraw() = runTest {
        val host = CountingHost()
        WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L).start()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(1, host.continueListening) // the first state

        repeat(5) { i -> repo.progress.value = listOf(ReadingProgress("a", (i + 1) * 10_000L, 100_000L, i.toLong())) }
        runCurrent()
        assertEquals(1, host.continueListening)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(2, host.continueListening)
    }

    @Test
    fun resumeStartsTheContinueBookAndOpensThePlayer() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))

        assertTrue(resumer.resume("a").isSuccess)

        assertEquals(listOf("a" to 25_000L), controller.loads)
        assertEquals(1, controller.plays)
        assertTrue(openPlayer.pending.value)
    }

    @Test
    fun resumeRefusesABookTheWidgetDoesNotOffer() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))

        // "b" was never started, and an unknown id is not a book at all: neither is played.
        assertTrue(resumer.resume("b").isFailure)
        assertTrue(resumer.resume("missing").isFailure)

        assertEquals(emptyList(), controller.loads)
        assertFalse(openPlayer.pending.value)
    }

    @Test
    fun resumeOfflineDoesNotStreamABookThatIsNotDownloaded() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        connectivity.online.value = false

        val result = resumer.resume("a")

        assertTrue(result.isFailure)
        assertEquals(emptyList(), controller.loads)
        assertFalse(openPlayer.pending.value)
    }

    @Test
    fun aFailedStartDoesNotOpenThePlayer() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        controller.onLoad = { _, _ -> throw IllegalStateException("server down") }

        val result = resumer.resume("a")

        assertTrue(result.isFailure)
        assertFalse(openPlayer.pending.value)
    }
}
