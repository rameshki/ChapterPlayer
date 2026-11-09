package com.chapterplayer.data.widgets

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.data.playback.CANNOT_REACH_TEXT
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.domain.widgets.ANOTHER_BOOK_PLAYING_TEXT
import com.chapterplayer.domain.widgets.WIDGET_START_WAIT_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.NowPlayingWidgetState
import com.chapterplayer.domain.widgets.SERVER_NOT_REACHABLE_TEXT
import com.chapterplayer.domain.widgets.WidgetStartReaction
import com.chapterplayer.domain.widgets.widgetStartReaction
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.playback.NEEDS_CONNECTION_TO_PLAY_TEXT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.EmptyCoroutineContext
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
    private fun TestScope.resumer() = WidgetResumer(states, repo, starter, openPlayer, backgroundScope, EmptyCoroutineContext)

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

        assertTrue(resumer().resume("a").isSuccess)

        assertEquals(listOf("a" to 25_000L), controller.loads)
        assertEquals(1, controller.plays)
        assertTrue(openPlayer.pending.value)
    }

    @Test
    fun resumeRefusesABookTheWidgetDoesNotOffer() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))

        // "b" was never started, and an unknown id is not a book at all: neither is played.
        assertTrue(resumer().resume("b").isFailure)
        assertTrue(resumer().resume("missing").isFailure)

        assertEquals(emptyList(), controller.loads)
        assertFalse(openPlayer.pending.value)
    }

    @Test
    fun resumeOfflineDoesNotStreamABookThatIsNotDownloaded() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        connectivity.online.value = false

        val result = resumer().resume("a")

        // A widget drawn before the connection went: the starter's own reason is shown.
        assertEquals(NEEDS_CONNECTION_TO_PLAY_TEXT, result.exceptionOrNull()?.message)
        assertEquals(emptyList(), controller.loads)
        assertFalse(openPlayer.pending.value)
    }

    @Test
    fun aDoubleTapRunsOneStart() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        val gate = CompletableDeferred<Unit>()
        controller.onLoad = { book, _ ->
            gate.await()
            controller.playback.value = PlaybackState(bookId = book.id, durationMs = book.durationMs)
        }
        val failures = mutableListOf<String>()
        val resumer = resumer()

        resumer.launch("a") { failures += it }
        runCurrent()
        resumer.launch("a") { failures += it }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("a" to 25_000L), controller.loads)
        assertEquals(emptyList(), failures) // the second tap is not an error
        assertTrue(openPlayer.pending.value)
    }

    @Test
    fun aBackgroundResumeReportsItsFailure() = runTest {
        val failures = mutableListOf<String>()
        resumer().launch("missing") { failures += it }
        runCurrent()
        assertEquals(listOf(WidgetResumer.NOTHING_TO_RESUME), failures)
    }

    // -- The widgets' headless Play (no app, no player screen) ---------------------------------

    @Test
    fun headlessPlayStartsTheBookWithoutOpeningTheAppAndTheWidgetsFlip() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        // All three offer the same book first.
        assertEquals("a", assertIs<ListenWidgetState.Resume>(states.listen.first()).bookId)
        assertEquals("a", assertIs<NowPlayingWidgetState.Resume>(states.nowPlaying.first()).bookId)

        val reactions = mutableListOf<WidgetStartReaction>()
        resumer().launchHeadless("a") { reactions += it }
        runCurrent()

        assertEquals(listOf("a" to 25_000L), controller.loads)
        assertEquals(1, controller.plays)
        assertFalse(openPlayer.pending.value) // no player screen is asked for
        assertEquals(emptyList(), reactions)
        assertIs<ListenWidgetState.Controls>(states.listen.first())
        assertIs<NowPlayingWidgetState.Book>(states.nowPlaying.first())
    }

    @Test
    fun headlessPlayOfflineSaysWhyAndKeepsTheResumeCard() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        connectivity.online.value = false // the widget was drawn before the connection went
        val reactions = mutableListOf<WidgetStartReaction>()
        resumer().launchHeadless("a") { reactions += it }
        runCurrent()

        assertEquals(listOf(WidgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT, offerApp = false)), reactions)
        assertEquals(emptyList(), controller.loads)
        assertFalse(openPlayer.pending.value)
    }

    @Test
    fun headlessPlayOpensTheAppOnlyToSignInAgain() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        controller.onLoad = { _, _ -> throw PlaybackStartException("Sign in again in Settings") }
        val reactions = mutableListOf<WidgetStartReaction>()
        resumer().launchHeadless("a") { reactions += it }
        runCurrent()
        assertEquals(listOf(WidgetStartReaction("Sign in again in Settings", offerApp = true)), reactions)

        // A server that cannot be reached: a toast only.
        controller.onLoad = { _, _ -> throw PlaybackStartException("Cannot reach the server") }
        reactions.clear()
        resumer().launchHeadless("a") { reactions += it }
        runCurrent()
        assertEquals(listOf(WidgetStartReaction(SERVER_NOT_REACHABLE_TEXT, offerApp = false)), reactions)
        // Still the Resume card: nothing was loaded.
        assertIs<ListenWidgetState.Resume>(states.listen.first())
    }

    // -- A widget Play never replaces a loaded book ----------------------------------------------

    @Test
    fun theCarPlayingAIsNotReplacedByContinueListeningsB() = runTest {
        // The car streams A while "Downloaded only" is on (the car ignores it): Continue offers the downloaded B.
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 5L), ReadingProgress("b", 10_000L, 100_000L, 1L))
        downloads.states.value = mapOf("b" to DownloadState.DOWNLOADED)
        settings.downloadedOnlyFlow.value = true
        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, durationMs = 100_000L)
        assertEquals("b", assertIs<ContinueWidgetState.Book>(states.continueListening.first()).bookId)

        val reactions = mutableListOf<WidgetStartReaction>()
        resumer().launchHeadless("b") { reactions += it }
        runCurrent()

        assertEquals(emptyList(), controller.loads) // A stays
        assertEquals("a", controller.playback.value.bookId)
        assertEquals(listOf(WidgetStartReaction(ANOTHER_BOOK_PLAYING_TEXT, offerApp = false)), reactions)
    }

    @Test
    fun aStaleListenCardDoesNotReplaceTheBookLoadedSinceItWasDrawn() = runTest {
        repo.progress.value = listOf(ReadingProgress("b", 25_000L, 100_000L, 2L))
        assertEquals("b", assertIs<ListenWidgetState.Resume>(states.listen.first()).bookId) // the card is drawn with B
        // Then the notification or a headset loads A (paused); the redraw has not happened yet.
        controller.playback.value = PlaybackState(bookId = "a", durationMs = 100_000L)

        val reactions = mutableListOf<WidgetStartReaction>()
        resumer().launchHeadless("b") { reactions += it }
        runCurrent()

        assertEquals(emptyList(), controller.loads)
        assertEquals(0, controller.plays)
        assertEquals(listOf(ANOTHER_BOOK_PLAYING_TEXT), reactions.map { it.text })
    }

    @Test
    fun anotherCopyOfTheTappedBookPlaysOnInsteadOfBeingReloaded() = runTest {
        // One book on two servers is one entry; the Plex copy is loaded and paused, the card names the ABS copy.
        val abs = testBook("abs_c", title = "Gamma", author = "Cy").copy(durationMs = 100_000L)
        val plex = testBook("plex_c", title = "Gamma", author = "Cy").copy(durationMs = 100_000L, serverId = "s2")
        val two = FakeBookRepository(listOf(abs, plex))
        two.progress.value = listOf(ReadingProgress("abs_c", 25_000L, 100_000L, 2L))
        val merging = object : com.chapterplayer.data.merge.SourceMerging {
            override val context = kotlinx.coroutines.flow.flowOf<com.chapterplayer.domain.browse.MergeContext?>(com.chapterplayer.domain.browse.MergeContext())
        }
        val twoStates = WidgetStates(controller, two, downloads, connectivity, settings, merging)
        val resumer = WidgetResumer(twoStates, two, PlaybackStarter(controller, two, settings, connectivity, FakeLocalBookFiles()), openPlayer, backgroundScope, EmptyCoroutineContext)
        controller.playback.value = PlaybackState(bookId = "plex_c", durationMs = 100_000L)

        val reactions = mutableListOf<WidgetStartReaction>()
        resumer.launchHeadless("abs_c") { reactions += it }
        runCurrent()

        assertEquals(emptyList(), controller.loads) // the loaded copy is not swapped
        assertEquals(1, controller.plays) // it just plays on
        assertEquals(emptyList(), reactions)
    }

    // -- The widget callback waits for the start (bounded) ---------------------------------------

    @Test
    fun theCallbackWaitsForAQuickStart() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        assertTrue(resumer().playFromWidget("a") { })
        assertEquals(listOf("a" to 25_000L), controller.loads)
    }

    @Test
    fun theCallbackWaitsForAFailureAndItsToast() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        controller.onLoad = { _, _ -> throw PlaybackStartException(CANNOT_REACH_TEXT) }
        val reactions = mutableListOf<WidgetStartReaction>()
        assertTrue(resumer().playFromWidget("a") { reactions += it })
        assertEquals(listOf(SERVER_NOT_REACHABLE_TEXT), reactions.map { it.text }) // reported before the callback returns
    }

    @Test
    fun aSlowStartOutlastsTheWaitAndStillFinishes() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        controller.onLoad = { book, _ ->
            delay(WIDGET_START_WAIT_MS + 10_000L) // a server far slower than the wait
            controller.playback.value = PlaybackState(bookId = book.id, durationMs = book.durationMs)
        }
        val started = testScheduler.currentTime
        assertFalse(resumer().playFromWidget("a") { })
        assertEquals(WIDGET_START_WAIT_MS, testScheduler.currentTime - started) // bounded
        advanceTimeBy(11_000L)
        runCurrent()
        assertEquals("a", controller.playback.value.bookId) // the start went on in the app scope
    }

    @Test
    fun cancellingTheWaitDoesNotCancelTheStart() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        val gate = CompletableDeferred<Unit>()
        controller.onLoad = { book, _ ->
            gate.await()
            controller.playback.value = PlaybackState(bookId = book.id, durationMs = book.durationMs)
        }
        val resumer = resumer()
        val waiting = launch { resumer.playFromWidget("a") { } }
        runCurrent()
        waiting.cancel() // the broadcast went away
        gate.complete(Unit)
        runCurrent()
        assertEquals("a", controller.playback.value.bookId)
    }

    @Test
    fun headlessPlayKeepsTheSafetyRules() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        val reactions = mutableListOf<WidgetStartReaction>()
        // A book the widget does not offer (another app sending the broadcast): refused.
        resumer().launchHeadless("b") { reactions += it }
        runCurrent()
        assertEquals(emptyList(), controller.loads)
        assertEquals(listOf(widgetStartReaction(WidgetResumer.NOTHING_TO_RESUME)), reactions)

        // A double tap: one start, and the second tap is not an error.
        val gate = CompletableDeferred<Unit>()
        controller.onLoad = { book, _ ->
            gate.await()
            controller.playback.value = PlaybackState(bookId = book.id, durationMs = book.durationMs)
        }
        reactions.clear()
        val resumer = resumer()
        resumer.launchHeadless("a") { reactions += it }
        runCurrent()
        resumer.launchHeadless("a") { reactions += it }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a" to 25_000L), controller.loads)
        assertEquals(emptyList(), reactions)
    }

    @Test
    fun aFailedStartDoesNotOpenThePlayer() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        controller.onLoad = { _, _ -> throw IllegalStateException("server down") }

        val result = resumer().resume("a")

        assertTrue(result.isFailure)
        assertFalse(openPlayer.pending.value)
    }
}
