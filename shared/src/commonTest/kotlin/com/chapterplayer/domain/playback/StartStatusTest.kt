package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class StartStatusTest {

    private val starting = StartStatus.Starting(bookId = "a", title = "Alpha", attempt = 1)
    private val failed = StartStatus.Failed(bookId = "a", title = "Alpha", message = "Needs a connection to play", attempt = 1)

    @Test
    fun aLoadedBookIsAlwaysShown() {
        assertEquals(PlayerEmptyView.NONE, playerEmptyView(bookLoaded = true, status = StartStatus.Idle))
        assertEquals(PlayerEmptyView.NONE, playerEmptyView(bookLoaded = true, status = starting))
        assertEquals(PlayerEmptyView.NONE, playerEmptyView(bookLoaded = true, status = failed))
    }

    @Test
    fun aStartInFlightIsLoadingNeverNothingPlaying() {
        assertEquals(PlayerEmptyView.STARTING, playerEmptyView(bookLoaded = false, status = starting))
    }

    @Test
    fun aFailedStartShowsItsReason() {
        assertEquals(PlayerEmptyView.FAILED, playerEmptyView(bookLoaded = false, status = failed))
    }

    @Test
    fun nothingLoadedAndNothingStartingLeavesThePlayer() {
        // A player screen left on the back stack (restored after process death, or after the idle release) closes.
        assertEquals(PlayerEmptyView.LEAVE, playerEmptyView(bookLoaded = false, status = StartStatus.Idle))
    }

    @Test
    fun aFailureShowsOnlyIfItsStartWasWatchedAndItIsRecent() {
        val f = StartStatus.Failed("a", "Alpha", "x", attempt = 3, atMs = 1_000L)
        assertEquals(f, visibleStartStatus(f, watchedFrom = 3, nowMs = 1_000L))
        assertEquals(f, visibleStartStatus(f, watchedFrom = 2, nowMs = 1_000L + START_FAILURE_SHOWN_MS - 1))
        // Old news.
        assertEquals(StartStatus.Idle, visibleStartStatus(f, watchedFrom = 3, nowMs = 1_000L + START_FAILURE_SHOWN_MS))
        // Begun before the player was watched (the car, a widget, another book): never shown.
        assertEquals(StartStatus.Idle, visibleStartStatus(f, watchedFrom = 4, nowMs = 1_000L))
        // Other statuses pass through.
        assertEquals(starting, visibleStartStatus(starting, watchedFrom = 9, nowMs = 0L))
    }

    @Test
    fun onlyTheLatestAttemptEnds() {
        val first = StartStatus.Starting("a", "Alpha", attempt = 1)
        val second = StartStatus.Starting("b", "Beta", attempt = 2)
        // The first start ends while the second runs: the second keeps the screen.
        assertEquals(second, afterStart(current = second, mine = first, result = StartStatus.Idle))
        assertEquals(StartStatus.Idle, afterStart(current = second, mine = second, result = StartStatus.Idle))
        val failedSecond = StartStatus.Failed("b", "Beta", "x", attempt = 2)
        assertEquals(failedSecond, afterStart(current = second, mine = second, result = failedSecond))
    }
}
