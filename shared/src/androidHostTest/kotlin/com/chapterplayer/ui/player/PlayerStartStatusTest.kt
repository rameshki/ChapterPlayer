package com.chapterplayer.ui.player

import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.playback.NEEDS_CONNECTION_TO_PLAY_TEXT
import com.chapterplayer.domain.playback.PlayerEmptyView
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "nothing is playing" landing seen on the phone after a widget's Play: the full player must never claim nothing is playing
 * while a start is under way, must show why a start failed, and closes itself when it was only left on the back stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStartStatusTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val book = testBook("a", title = "Alpha").copy(durationMs = 100_000L)
    private val controller = FakePlaybackController()
    private val repo = FakeBookRepository(listOf(book))
    private val connectivity = FakeConnectivity()
    private val settings = FakeAppSettings()
    private val starter = PlaybackStarter(controller, repo, settings, connectivity, FakeLocalBookFiles())
    // Built after setUp put the test dispatcher in place of Main.
    private val vm by lazy { PlayerViewModel(controller, repo, settings, Dispatchers.Main, starter) }

    private fun TestScope.ui(): () -> PlayerUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return { vm.uiState.value }
    }

    @Test
    fun aPlayerLeftOnTheStackWithNothingLoadedCloses() = runTest {
        // Restored after process death, or the player released after 10 min paused: it does not say "Nothing is playing".
        val state = ui()
        assertEquals(PlayerEmptyView.LEAVE, state().emptyView)
    }

    @Test
    fun aStartInFlightShowsLoadingWithTheTitle() = runTest {
        val gate = CompletableDeferred<Unit>()
        controller.onLoad = { b, _ ->
            gate.await()
            controller.playback.value = PlaybackState(bookId = b.id, durationMs = b.durationMs)
        }
        val state = ui()
        backgroundScope.launch { starter.start(book) }
        runCurrent()
        assertEquals(PlayerEmptyView.STARTING, state().emptyView)
        assertEquals("Alpha", state().startingTitle)

        gate.complete(Unit)
        runCurrent()
        assertEquals(PlayerEmptyView.NONE, state().emptyView)
    }

    @Test
    fun aFailedStartShowsTheRealReason() = runTest {
        connectivity.online.value = false
        val state = ui()
        starter.start(book)
        assertEquals(PlayerEmptyView.FAILED, state().emptyView)
        assertEquals(NEEDS_CONNECTION_TO_PLAY_TEXT, state().startError)

        // Seen: Back forgets it, so the player is not left showing an old failure.
        vm.dismissStartFailure()
        assertEquals(PlayerEmptyView.LEAVE, state().emptyView)
        assertNull(state().startError)
    }

    @Test
    fun aStartThatThrowsInThePlayerIsAFailureNotNothing() = runTest {
        controller.onLoad = { _, _ -> throw IllegalStateException("player gone") }
        val state = ui()
        starter.start(book)
        assertEquals(PlayerEmptyView.FAILED, state().emptyView)
        assertEquals("Could not start playback", state().startError)
    }
}
