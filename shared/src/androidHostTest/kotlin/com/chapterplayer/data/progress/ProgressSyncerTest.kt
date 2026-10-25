package com.chapterplayer.data.progress

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import com.chapterplayer.testing.testSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSyncerTest {

    private class Rig(scope: CoroutineScope, now: () -> Long) {
        val controller = FakePlaybackController()
        val repo = FakeBookRepository(
            listOf(testBook("a"), testBook("b"), testBook("plex").copy(source = ServerSource.PLEX))
        )
        var queuedSignals = 0
        val syncer = ProgressSyncer(controller, repo, scope, now, onQueued = { queuedSignals++ }).also { it.start() }
    }

    private fun TestScope.rig() = Rig(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        { testScheduler.currentTime }
    )

    private fun Rig.play(book: String = "a", pos: Long, playing: Boolean = true, session: Boolean = true) {
        controller.session = if (session) testSession(book) else null
        controller.playback.value = PlaybackState(bookId = book, isPlaying = playing, positionMs = pos, durationMs = 100_000L)
    }

    @Test
    fun `while playing the server hears the position every ten seconds with the time listened`() = runTest {
        val r = rig()
        r.play(pos = 1_000)
        advanceTimeBy(9_500)
        assertTrue(r.repo.syncCalls.isEmpty())
        r.play(pos = 10_000)
        advanceTimeBy(1_000)
        r.play(pos = 20_000)
        advanceTimeBy(10_000)
        assertEquals(listOf(10_000L to 10_000L, 20_000L to 10_000L), r.repo.syncCalls.map { it.positionMs to it.listenedMs })
        assertEquals("sess-a", r.repo.syncCalls.first().session.sessionId)
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `nothing is sent while paused`() = runTest {
        val r = rig()
        r.play(pos = 5_000, playing = false)
        advanceTimeBy(120_000)
        assertTrue(r.repo.syncCalls.isEmpty())
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `pausing sends a final sync with the time listened since the last one`() = runTest {
        val r = rig()
        r.play(pos = 1_000)
        advanceTimeBy(10_500)
        r.play(pos = 14_000, playing = false)
        assertEquals(listOf(1_000L to 10_000L, 14_000L to 500L), r.repo.syncCalls.map { it.positionMs to it.listenedMs })
        // The periodic report is made while playing, the last one says playback stopped.
        assertEquals(listOf(true, false), r.repo.syncCalls.map { it.isPlaying })
        advanceTimeBy(120_000)
        assertEquals(2, r.repo.syncCalls.size)
    }

    @Test
    fun `listened time is capped at one minute and never negative`() = runTest {
        var clock = 1_000_000L
        val c = FakePlaybackController()
        val repo = FakeBookRepository(listOf(testBook("a")))
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        ProgressSyncer(c, repo, scope, { clock }).start()
        c.session = testSession("a")
        c.playback.value = PlaybackState(bookId = "a", isPlaying = true, positionMs = 1_000, durationMs = 100_000L)
        clock += 500_000
        c.playback.value = c.playback.value.copy(isPlaying = false)
        assertEquals(60_000L, repo.syncCalls.single().listenedMs)
        c.playback.value = c.playback.value.copy(isPlaying = true)
        clock -= 5_000
        c.playback.value = c.playback.value.copy(isPlaying = false)
        assertEquals(0L, repo.syncCalls.last().listenedMs)
    }

    @Test
    fun `another book taking over syncs the old book at its own last position`() = runTest {
        val r = rig()
        r.play("a", pos = 20_000)
        advanceTimeBy(3_000)
        r.play("b", pos = 10)
        val call = r.repo.syncCalls.single()
        assertEquals("a", call.session.bookId)
        assertEquals(20_000L, call.positionMs)
        assertEquals(3_000L, call.listenedMs)
    }

    @Test
    fun `releasing while playing syncs the book with its last position`() = runTest {
        val r = rig()
        r.play(pos = 42_000)
        advanceTimeBy(2_000)
        r.controller.session = null
        r.controller.playback.value = PlaybackState()
        assertEquals(42_000L, r.repo.syncCalls.single().positionMs)
        assertEquals("sess-a", r.repo.syncCalls.single().session.sessionId)
    }

    @Test
    fun `a failed sync queues the position once, merged with the earlier failure`() = runTest {
        val r = rig()
        r.repo.onSyncPlayback = { Result.failure(RuntimeException("offline")) }
        r.play(pos = 1_000)
        advanceTimeBy(10_500)
        r.play(pos = 12_000)
        advanceTimeBy(10_000)
        val row = r.repo.queued.single()
        assertEquals("a", row.bookId)
        assertEquals("s1", row.serverId)
        assertEquals(12_000L, row.positionMs)
        assertEquals(100_000L, row.durationMs)
        assertEquals(20_000L, row.listenedMs)
        assertEquals(20_000L, row.updatedAtMs)
        assertEquals(2, r.queuedSignals)
    }

    @Test
    fun `a sync that throws queues the position and keeps watching`() = runTest {
        val r = rig()
        var calls = 0
        r.repo.onSyncPlayback = { calls++; error("boom") }
        r.play(pos = 1_000)
        advanceTimeBy(10_500)
        advanceTimeBy(10_000)
        assertEquals(2, calls)
        assertEquals(1, r.repo.queued.size)
    }

    @Test
    fun `no session queues the position instead`() = runTest {
        val r = rig()
        r.play(pos = 7_000, session = false)
        advanceTimeBy(10_500)
        assertTrue(r.repo.syncCalls.isEmpty())
        assertEquals(PendingProgress("a", "s1", 7_000, 100_000, 10_000, 10_000), r.repo.queued.single())
    }

    @Test
    fun `a book of a Plex server is reported like any other`() = runTest {
        val r = rig()
        r.play("plex", pos = 7_000)
        advanceTimeBy(10_500)
        r.play("plex", pos = 9_000, playing = false)
        assertEquals(listOf(7_000L to true, 9_000L to false), r.repo.syncCalls.map { it.positionMs to it.isPlaying })
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a live sync the server took drops the older queued position of the book, but never a queued mark`() = runTest {
        val r = rig()
        r.repo.queueProgress(PendingProgress("a", "s1", 50_000, 100_000, 0, updatedAtMs = 0))
        r.repo.queueProgress(PendingProgress("b", "s1", 9_000, 100_000, 0, updatedAtMs = 0, markedFinished = true))
        r.play("a", pos = 7_000)
        advanceTimeBy(10_500)
        assertEquals(listOf("b"), r.repo.queued.map { it.bookId })

        r.play("b", pos = 1_000)
        advanceTimeBy(10_500)
        assertEquals(listOf("b"), r.repo.queued.map { it.bookId })
        assertEquals(true, r.repo.queued.single().markedFinished)
    }

    @Test
    fun `a failed live sync keeps the queue`() = runTest {
        val r = rig()
        r.repo.onSyncPlayback = { Result.failure(IllegalStateException("down")) }
        r.play("a", pos = 7_000)
        advanceTimeBy(10_500)
        assertEquals(7_000L, r.repo.queued.single().positionMs)
    }

    @Test
    fun `a Plex position the server did not get is queued`() = runTest {
        val r = rig()
        r.play("plex", pos = 7_000, session = false)
        advanceTimeBy(10_500)
        assertEquals("plex", r.repo.queued.single().bookId)
    }

    @Test
    fun `a book that is not in the library is skipped`() = runTest {
        val r = rig()
        r.play("ghost", pos = 7_000)
        advanceTimeBy(30_000)
        assertTrue(r.repo.syncCalls.isEmpty())
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `position zero is not reported`() = runTest {
        val r = rig()
        r.play(pos = 0)
        advanceTimeBy(30_000)
        assertTrue(r.repo.syncCalls.isEmpty())
    }

    @Test
    fun `cancellation inside a sync is not swallowed`() = runTest {
        val r = rig()
        r.repo.onSyncPlayback = { throw CancellationException("stop") }
        r.play(pos = 1_000)
        advanceTimeBy(10_500)
        // The observer ended with it: later playing is never reported and nothing was queued.
        advanceTimeBy(30_000)
        assertEquals(1, r.repo.syncCalls.size)
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `starting twice observes once`() = runTest {
        val r = rig()
        r.syncer.start()
        r.play(pos = 1_000)
        advanceTimeBy(10_500)
        assertEquals(1, r.repo.syncCalls.size)
    }

    @Test
    fun `listened time counts from the moment playing resumed`() = runTest {
        val r = rig()
        r.play(pos = 1_000)
        advanceTimeBy(4_000)
        r.play(pos = 5_000, playing = false)
        advanceTimeBy(50_000)
        r.play(pos = 5_000)
        advanceTimeBy(2_000)
        r.play(pos = 7_000, playing = false)
        assertEquals(listOf(4_000L, 2_000L), r.repo.syncCalls.map { it.listenedMs })
    }
}
