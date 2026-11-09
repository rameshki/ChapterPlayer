package com.chapterplayer.data.widgets

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.domain.widgets.COVER_MAX_ATTEMPTS
import com.chapterplayer.domain.widgets.COVER_RETRY_MS
import com.chapterplayer.domain.widgets.ContinueWidgetState
import com.chapterplayer.domain.widgets.CoverFetch
import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ListenWidgetWiringTest {

    private val chapters = listOf(Chapter(0, "One", 0, 40_000), Chapter(1, "Two", 40_000, 100_000))
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
    private val actions = WidgetActions(controller)

    @Test
    fun bothWidgetsOfferTheSameBook() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L), ReadingProgress("b", 10_000L, 100_000L, 1L))
        val offered = assertIs<ContinueWidgetState.Book>(states.continueListening.first())
        assertEquals(offered.bookId, assertIs<ListenWidgetState.Resume>(states.listen.first()).bookId)

        // Offline only the downloaded book can play: both switch to it.
        connectivity.online.value = false
        downloads.states.value = mapOf("b" to DownloadState.DOWNLOADED)
        assertEquals("b", assertIs<ContinueWidgetState.Book>(states.continueListening.first()).bookId)
        assertEquals("b", assertIs<ListenWidgetState.Resume>(states.listen.first()).bookId)
    }

    @Test
    fun nothingPlayedYet() = runTest {
        assertEquals(ListenWidgetState.Empty(everPlayed = false), states.listen.first())
    }

    @Test
    fun playFromResumeFlipsToTheControlsAndAReleaseFlipsBack() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        assertIs<ListenWidgetState.Resume>(states.listen.first())

        // The Play button goes through the widget Resume path (as Continue Listening's Resume).
        val openPlayer = OpenPlayerRequest()
        val starter = PlaybackStarter(controller, repo, settings, connectivity, FakeLocalBookFiles())
        val resumer = WidgetResumer(states, repo, starter, openPlayer, backgroundScope, EmptyCoroutineContext)
        assertTrue(resumer.resume("a").isSuccess)

        val playing = assertIs<ListenWidgetState.Controls>(states.listen.first())
        assertEquals("a", playing.bookId)
        assertEquals("Alpha", playing.title)
        assertTrue(playing.isPlaying) // asked to play, buffering: Pause is offered

        controller.pause()
        assertFalse(assertIs<ListenWidgetState.Controls>(states.listen.first()).isPlaying)

        // The idle stop empties the player: Resume again.
        controller.playback.value = PlaybackState()
        assertEquals("a", assertIs<ListenWidgetState.Resume>(states.listen.first()).bookId)
    }

    @Test
    fun chapterButtonsActOnTheLoadedBookOnly() {
        assertFalse(actions.previousChapter())
        assertFalse(actions.nextChapter())
        assertEquals(emptyList(), controller.seeks)

        controller.playback.value = PlaybackState(bookId = "a", positionMs = 50_000, durationMs = 100_000, chapterIndex = 1, chapters = chapters)
        assertTrue(actions.nextChapter()) // the last chapter: nothing to jump to, but the app need not open
        assertEquals(emptyList(), controller.seeks)
        assertTrue(actions.previousChapter()) // 10 s into the chapter: back to its start
        assertEquals(listOf(40_000L), controller.seeks)

        controller.playback.value = controller.playback.value.copy(positionMs = 1_000, chapterIndex = 0)
        assertTrue(actions.nextChapter())
        assertEquals(listOf(40_000L, 40_000L), controller.seeks)
        assertEquals(emptyList(), controller.loads) // never loads a book
    }

    @Test
    fun theRefresherRedrawsControlsAtOnceAndResumeChangesQuietly() = runTest {
        val host = CountingHost()
        WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L).start()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(1, host.listen) // the first state

        // Progress bursts while nothing is loaded: one redraw after the quiet time.
        repeat(5) { i -> repo.progress.value = listOf(ReadingProgress("a", (i + 1) * 10_000L, 100_000L, i.toLong())) }
        runCurrent()
        assertEquals(1, host.listen)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(2, host.listen)

        // A book loads: the controls are drawn at once.
        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, positionMs = 50_000, durationMs = 100_000)
        runCurrent()
        assertEquals(3, host.listen)

        // Ticks inside one percent: nothing to redraw.
        repeat(5) { controller.playback.value = controller.playback.value.copy(positionMs = 50_000L + (it + 1) * 100L) }
        runCurrent()
        assertEquals(3, host.listen)

        controller.pause()
        runCurrent()
        assertEquals(4, host.listen)
    }

    @Test
    fun aCoverThatArrivesRedrawsTheWidgetOnce() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        val gate = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val have = mutableSetOf<String>()
        val covers = WidgetCovers { id ->
            asked += id
            if (id in have) CoverFetch.CACHED else {
                gate.await()
                have += id
                CoverFetch.LOADED
            }
        }
        val host = CountingHost()
        WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L, covers = covers).start()
        runCurrent()
        assertEquals(listOf("a"), asked)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(1, host.listen) // drawn without the cover: the load never blocks a redraw

        gate.complete(Unit)
        runCurrent()
        assertEquals(2, host.listen) // the cover arrived

        // Later changes find it cached: no further redraw for the cover.
        controller.playback.value = PlaybackState(bookId = "a", durationMs = 100_000)
        runCurrent()
        assertEquals(3, host.listen) // the controls only
    }

    @Test
    fun aFailingCoverLoadIsIgnored() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        val host = CountingHost()
        WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L, covers = { error("decoder gone") }).start()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(1, host.listen)
        controller.playback.value = PlaybackState(bookId = "a", durationMs = 100_000)
        runCurrent()
        assertEquals(2, host.listen)
    }

    @Test
    fun aFailedCoverIsTriedAgainAfterTheBackoffAndThenStops() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        val results = ArrayDeque(listOf(CoverFetch.FAILED, CoverFetch.LOADED))
        var asks = 0
        val host = CountingHost()
        WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L, covers = { asks++; results.removeFirstOrNull() ?: CoverFetch.CACHED }).start()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(1, asks)
        assertEquals(1, host.listen)

        advanceTimeBy(COVER_RETRY_MS)
        runCurrent()
        assertEquals(2, asks) // after the loader's backoff
        assertEquals(2, host.listen) // the cover arrived: one redraw

        advanceTimeBy(10 * COVER_RETRY_MS)
        runCurrent()
        assertEquals(2, asks) // nothing more: no loop
        assertEquals(2, host.listen)
    }

    @Test
    fun aBookWithoutACoverIsAskedForAFewTimesOnly() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        var asks = 0
        WidgetRefresher(states, CountingHost(), backgroundScope, continueQuietMs = 1_000L, covers = { asks++; CoverFetch.FAILED }).start()
        advanceTimeBy(20 * COVER_RETRY_MS)
        runCurrent()
        assertEquals(COVER_MAX_ATTEMPTS, asks)
    }

    @Test
    fun theNetworkComingBackAsksForTheCoverAgain() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        downloads.states.value = mapOf("a" to DownloadState.DOWNLOADED) // offered offline too: the state does not change
        connectivity.online.value = false
        var online = false
        var asks = 0
        val host = CountingHost()
        WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L, covers = { asks++; if (online) CoverFetch.LOADED else CoverFetch.FAILED }).start()
        advanceTimeBy(5 * COVER_RETRY_MS)
        runCurrent()
        assertEquals(1, asks) // offline: no retries
        val drawn = host.listen

        online = true
        connectivity.online.value = true
        runCurrent()
        assertEquals(2, asks)
        assertEquals(drawn + 1, host.listen)
    }

    @Test
    fun theWidgetAskingForItsCoverFetchesAMissingOne() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        var asks = 0
        val refresher = WidgetRefresher(states, CountingHost(), backgroundScope, continueQuietMs = 1_000L, covers = { asks++; CoverFetch.CACHED })
        refresher.start()
        runCurrent()
        assertEquals(1, asks)
        // Placed again, resized or updated by the system with the same state: the cover is asked for again.
        refresher.requestCover()
        runCurrent()
        assertEquals(2, asks)
    }

    @Test
    fun nothingIsComputedOrLoadedWhileNoListenWidgetIsPlaced() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        var asks = 0
        val host = CountingHost().apply { placed = false }
        val refresher = WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L, covers = { asks++; CoverFetch.LOADED })
        refresher.start()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(0, host.listen)
        // Continue Listening shows the same book: its cover is loaded (the platform cache loads nothing while no
        // widget at all is placed), and the Listen widget is not drawn for it.
        assertEquals(1, asks)
        // The other widgets as before: their first state, then once more when the cover arrived.
        assertEquals(2, host.nowPlaying)
        assertEquals(0, host.listen) // the cover never draws a Listen widget that is not placed

        // The receiver reports the first Listen widget: drawn, and its cover loaded.
        refresher.listenPlacementChanged(true)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(1, asks)
        assertTrue(host.listen >= 1)
        assertIs<ListenWidgetState.Resume>(refresher.listen.first())

        // The last one removed: state changes no longer redraw it.
        refresher.listenPlacementChanged(false)
        runCurrent()
        val drawn = host.listen
        controller.playback.value = PlaybackState(bookId = "a", durationMs = 100_000)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(drawn, host.listen)
    }

    @Test
    fun aCoverRequestDuringTheRedrawsDoesNotCutThemShort() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        var loaded = false
        val host = CountingHost()
        val refresher = WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L, covers = {
            if (loaded) CoverFetch.CACHED else { loaded = true; CoverFetch.LOADED }
        })
        // Drawing Listen starts its session, whose provideGlance asks for the cover again before the others are drawn.
        host.duringListenRedraw = {
            refresher.requestCover()
            delay(10)
        }
        refresher.start()
        advanceTimeBy(5_000L)
        runCurrent()
        val nowPlaying = host.nowPlaying
        val continueListening = host.continueListening
        assertTrue(loaded)
        // The first states (1 each) and the cover's redraw (1 each), although a request came in during the Listen redraw.
        assertEquals(2, nowPlaying)
        assertEquals(2, continueListening)
    }

    @Test
    fun noCoverIsLoadedWhileNoWidgetIsPlaced() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 25_000L, 100_000L, 2L))
        var asks = 0
        val host = CountingHost().apply { somePlaced = false; placed = false }
        val refresher = WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L, covers = { asks++; CoverFetch.LOADED })
        refresher.start()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(0, asks)

        // A widget is drawn (placed): its covers are asked for.
        refresher.requestCover()
        runCurrent()
        assertEquals(1, asks)
    }

    @Test
    fun theOtherWidgetsAreRedrawnAsBefore() = runTest {
        val host = CountingHost()
        WidgetRefresher(states, host, backgroundScope, continueQuietMs = 1_000L).start()
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(1, host.nowPlaying)
        assertEquals(1, host.continueListening)
    }
}
