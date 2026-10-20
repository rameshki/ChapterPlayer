package com.chapterplayer.data.progress

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PendingProgressFlusherTest {

    private class Rig(scope: CoroutineScope, now: () -> Long, online: Boolean) {
        val repo = FakeBookRepository()
        val connectivity = FakeConnectivity(online)
        val flusher = PendingProgressFlusher(repo, connectivity, scope, now)
    }

    private fun TestScope.rig(online: Boolean = true) = Rig(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        { testScheduler.currentTime },
        online
    )

    private fun row(book: String = "a", at: Long = 100, attempts: Int = 0, next: Long = 0, pos: Long = 5_000) =
        PendingProgress(book, "s1", pos, 100_000, 3_000, at, attempts, next)

    private fun server(updatedAt: Long) = ServerProgress(9_000, 100_000, false, updatedAt)

    @Test
    fun `a row is pushed and removed when the server has nothing newer`() = runTest {
        val r = rig()
        r.repo.queued += row()
        r.flusher.start()
        runCurrent()
        assertEquals(listOf(row()), r.repo.pushed)
        assertTrue(r.repo.queued.isEmpty())
        assertTrue(r.repo.savedServer.isEmpty())
    }

    @Test
    fun `a server that is newer drops the row first and then stores its progress`() = runTest {
        val r = rig()
        r.repo.queued += row(at = 100)
        r.repo.onFetchServerProgress = { Result.success(server(updatedAt = 500)) }
        r.flusher.start()
        runCurrent()
        assertTrue(r.repo.pushed.isEmpty())
        assertEquals(listOf("delete a", "saveServer a"), r.repo.events)
        assertEquals(listOf("a" to server(500)), r.repo.savedServer)
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a server that is older than the row is overwritten`() = runTest {
        val r = rig()
        r.repo.queued += row(at = 100)
        r.repo.onFetchServerProgress = { Result.success(server(updatedAt = 50)) }
        r.flusher.start()
        runCurrent()
        assertEquals(1, r.repo.pushed.size)
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a failed push keeps the row and schedules the retry with the backoff`() = runTest {
        val r = rig()
        advanceTimeBy(1_000)
        r.repo.queued += row(at = 100)
        r.repo.onPushProgress = { Result.failure(RuntimeException("down")) }
        r.flusher.start()
        runCurrent()
        assertEquals(listOf(Triple("a", 1, 6_000L)), r.repo.attempts)
        assertEquals(1, r.repo.queued.single().attempts)
        // The retry comes by itself when the time has come, with a longer wait after the next failure.
        advanceTimeBy(5_001)
        assertEquals(2, r.repo.pushed.size)
        assertEquals(Triple("a", 2, 16_000L), r.repo.attempts.last())
    }

    @Test
    fun `a failed fetch counts as a failed attempt`() = runTest {
        val r = rig()
        r.repo.queued += row(attempts = 2)
        r.repo.onFetchServerProgress = { Result.failure(RuntimeException("down")) }
        r.flusher.start()
        runCurrent()
        assertTrue(r.repo.pushed.isEmpty())
        assertEquals(listOf(Triple("a", 3, 20_000L)), r.repo.attempts)
    }

    @Test
    fun `a fetch that throws is a failed attempt too`() = runTest {
        val r = rig()
        r.repo.queued += row()
        r.repo.onFetchServerProgress = { error("boom") }
        r.flusher.start()
        runCurrent()
        assertEquals(listOf(Triple("a", 1, 5_000L)), r.repo.attempts)
    }

    @Test
    fun `rows whose time has not come are skipped until it does`() = runTest {
        val r = rig()
        r.repo.queued += row("a", next = 30_000)
        r.repo.queued += row("b", next = 0)
        r.flusher.start()
        runCurrent()
        assertEquals(listOf("b"), r.repo.pushed.map { it.bookId })
        advanceTimeBy(29_000)
        assertEquals(1, r.repo.pushed.size)
        advanceTimeBy(1_500)
        assertEquals(listOf("b", "a"), r.repo.pushed.map { it.bookId })
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a refused sign-in stops the pass quietly`() = runTest {
        val r = rig()
        r.repo.queued += row("a")
        r.repo.queued += row("b")
        r.repo.onFetchServerProgress = { Result.failure(SignInRequiredException("S")) }
        r.flusher.start()
        runCurrent()
        assertEquals(listOf("a"), r.repo.fetchedProgress)
        assertTrue(r.repo.attempts.isEmpty())
        assertEquals(2, r.repo.queued.size)
        advanceTimeBy(600_000)
        assertEquals(listOf("a"), r.repo.fetchedProgress)
    }

    @Test
    fun `a refused sign-in on the push also stops without counting an attempt`() = runTest {
        val r = rig()
        r.repo.queued += row("a")
        r.repo.queued += row("b")
        r.repo.onPushProgress = { Result.failure(SignInRequiredException("S")) }
        r.flusher.start()
        runCurrent()
        assertEquals(listOf("a"), r.repo.pushed.map { it.bookId })
        assertTrue(r.repo.attempts.isEmpty())
    }

    @Test
    fun `coming online flushes once`() = runTest {
        val r = rig(online = false)
        r.repo.queued += row()
        r.flusher.start()
        runCurrent()
        assertTrue(r.repo.fetchedProgress.isEmpty())
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(1, r.repo.pushed.size)
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(1, r.repo.fetchedProgress.size)
    }

    @Test
    fun `going offline stops flushing, the kick while offline does nothing`() = runTest {
        val r = rig()
        r.flusher.start()
        runCurrent()
        r.connectivity.online.value = false
        runCurrent()
        r.repo.queued += row()
        r.flusher.kick()
        runCurrent()
        assertTrue(r.repo.fetchedProgress.isEmpty())
    }

    @Test
    fun `a kick after a queue write flushes the new row`() = runTest {
        val r = rig()
        r.flusher.start()
        runCurrent()
        r.repo.queued += row()
        r.flusher.kick()
        runCurrent()
        assertEquals(1, r.repo.pushed.size)
    }

    @Test
    fun `many triggers at once run one pass per row`() = runTest {
        val r = rig()
        val gate = CompletableDeferred<Unit>()
        r.repo.queued += row()
        r.repo.onFetchServerProgress = { gate.await(); Result.success(null) }
        r.flusher.start()
        repeat(5) { r.flusher.kick() }
        r.connectivity.online.value = false
        r.connectivity.online.value = true
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, r.repo.pushed.size)
        assertEquals(1, r.repo.fetchedProgress.size)
    }

    @Test
    fun `a position queued while a pass is working is delivered by the next pass`() = runTest {
        val r = rig()
        val gate = CompletableDeferred<Unit>()
        r.repo.queued += row("a", at = 100)
        r.repo.onFetchServerProgress = { if (it == "a") gate.await(); Result.success(null) }
        r.flusher.start()
        runCurrent()
        r.repo.queued += row("b", at = 200)
        r.flusher.kick()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b"), r.repo.pushed.map { it.bookId })
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a row queued again during its push stays for the next pass`() = runTest {
        val r = rig()
        r.repo.queued += row("a", at = 100, pos = 5_000)
        var first = true
        r.repo.onPushProgress = {
            if (first) {
                first = false
                r.repo.queued.clear()
                r.repo.queued += row("a", at = 300, pos = 8_000)
            }
            Result.success(Unit)
        }
        r.flusher.start()
        runCurrent()
        // The newer row was not deleted by the first push; the retry timer (none needed) or next trigger sends it.
        assertEquals(listOf(8_000L), r.repo.queued.map { it.positionMs })
        r.flusher.kick()
        runCurrent()
        assertEquals(listOf(5_000L, 8_000L), r.repo.pushed.map { it.positionMs })
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `starting twice runs one loop`() = runTest {
        val r = rig()
        r.repo.queued += row()
        r.flusher.start()
        r.flusher.start()
        runCurrent()
        assertEquals(1, r.repo.pushed.size)
    }

    @Test
    fun `a failing queue read does not end the flusher`() = runTest {
        val r = rig()
        var broken = true
        val repo = object : FakeBookRepository() {
            override suspend fun pendingProgress(): List<PendingProgress> {
                if (broken) error("db closed")
                return super.pendingProgress()
            }
        }
        val flusher = PendingProgressFlusher(
            repo, r.connectivity,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            { testScheduler.currentTime }
        )
        flusher.start()
        runCurrent()
        broken = false
        repo.queued += row()
        flusher.kick()
        runCurrent()
        assertEquals(1, repo.pushed.size)
    }
}
