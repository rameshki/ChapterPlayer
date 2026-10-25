package com.chapterplayer.data.widgets

import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.widgets.NowPlayingWidgetState
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingWidgetWiringTest {

    private val controller = FakePlaybackController()
    private val repo = FakeBookRepository(listOf(testBook("a", title = "Alpha", author = "Ann")))
    private val states = WidgetStates(controller, repo, FakeDownloadStates(), FakeConnectivity(), FakeAppSettings())
    private val actions = WidgetActions(controller)

    @Test
    fun theStateNamesTheLoadedBook() = runTest {
        assertEquals(NowPlayingWidgetState.Idle, states.nowPlaying.first())
        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, durationMs = 1_000)
        assertEquals(NowPlayingWidgetState.Book("Alpha", "Ann", null, isPlaying = true, canSkip = true), states.nowPlaying.first())
    }

    @Test
    fun playPauseActsOnTheLoadedBookOnly() {
        // Nothing loaded: the button says so, and the widget opens the app instead.
        assertFalse(actions.playPause())
        assertEquals(0, controller.plays)
        assertEquals(0, controller.pauses)

        controller.playback.value = PlaybackState(bookId = "a", durationMs = 1_000)
        assertTrue(actions.playPause())
        assertEquals(1, controller.plays)
        assertEquals(emptyList(), controller.loads) // never loads a book

        controller.soundStarts()
        actions.playPause()
        assertEquals(1, controller.pauses)
    }

    @Test
    fun skipsActOnTheLoadedBookOnly() {
        assertFalse(actions.skipBack())
        assertFalse(actions.skipForward())
        assertEquals(0, controller.skipBacks + controller.skipForwards)

        controller.playback.value = PlaybackState(bookId = "a", durationMs = 1_000)
        assertTrue(actions.skipBack())
        assertTrue(actions.skipForward())
        assertEquals(1, controller.skipBacks)
        assertEquals(1, controller.skipForwards)

        // A loaded book of unknown length: nothing to skip, but the app need not open either.
        controller.playback.value = PlaybackState(bookId = "a", durationMs = 0)
        assertTrue(actions.skipBack())
        assertEquals(1, controller.skipBacks)
    }

    @Test
    fun theRefresherRedrawsOnStateChangesNotOnTicks() = runTest {
        val host = CountingHost()
        val refresher = WidgetRefresher(states, host, backgroundScope)
        refresher.start()
        runCurrent()
        assertEquals(1, host.nowPlaying) // the first state is drawn (the app may have been gone)

        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, positionMs = 0, durationMs = 100_000)
        runCurrent()
        assertEquals(2, host.nowPlaying)

        // Position ticks while playing: nothing to redraw.
        repeat(5) { controller.playback.value = controller.playback.value.copy(positionMs = (it + 1) * 1_000L) }
        runCurrent()
        assertEquals(2, host.nowPlaying)

        controller.pause()
        runCurrent()
        assertEquals(3, host.nowPlaying)
    }

    @Test
    fun aFailingRedrawDoesNotStopTheRefresher() = runTest {
        val host = CountingHost(fail = true)
        val refresher = WidgetRefresher(states, host, backgroundScope)
        refresher.start()
        refresher.start() // a second start is ignored
        runCurrent()
        controller.playback.value = PlaybackState(bookId = "a", durationMs = 1_000)
        runCurrent()
        assertEquals(2, host.nowPlaying)
    }
}

/** Counts redraws; with [fail] every redraw throws after counting. */
class CountingHost(private val fail: Boolean = false) : WidgetHost {
    var nowPlaying = 0
    var continueListening = 0
    override suspend fun redrawNowPlaying() {
        nowPlaying++
        if (fail) error("widget host gone")
    }
    override suspend fun redrawContinueListening() {
        continueListening++
        if (fail) error("widget host gone")
    }
}
