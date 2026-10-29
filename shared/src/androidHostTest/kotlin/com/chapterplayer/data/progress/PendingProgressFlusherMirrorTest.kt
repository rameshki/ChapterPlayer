package com.chapterplayer.data.progress

import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the flusher does with a queued mirror (a position repeating another copy's listening). */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingProgressFlusherMirrorTest {

    private class Peers : ProgressMirrorPeers {
        var paired = true
        /** The lookup itself fails (the library or the corrections cannot be read). */
        var broken = false
        val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val changes: Flow<Unit> = changed
        override suspend fun peersOfAll(bookIds: Collection<String>): Map<String, List<MirrorPeer>>? =
            if (broken) null else bookIds.associateWith { peersOf(it) }
        override suspend fun peersOf(bookId: String): List<MirrorPeer> = when {
            !paired -> emptyList()
            bookId == "a" -> listOf(MirrorPeer("b", "s2", 120_000L))
            bookId == "b" -> listOf(MirrorPeer("a", "s1", 100_000L))
            else -> emptyList()
        }
    }

    private class Rig(scope: CoroutineScope, online: Boolean, now: () -> Long) {
        val repo = FakeBookRepository(listOf(testBook("a"), testBook("b")))
        val peers = Peers()
        val connectivity = FakeConnectivity(online)
        var loaded: String? = null
        val flusher = PendingProgressFlusher(repo, connectivity, scope, now, peers = peers, loadedBookId = { loaded }).also { it.start() }
    }

    private fun TestScope.rig(online: Boolean = true) =
        Rig(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), online) { 10_000L + testScheduler.currentTime }

    private val mirror = PendingProgress("b", "s2", 60_000L, 120_000L, 0L, updatedAtMs = 5_000L, mirrorOf = "a")

    @Test
    fun `a queued mirror is delivered when nothing newer is known`() = runTest {
        val r = rig()
        r.repo.progress.value = listOf(ReadingProgress("a", 50_000L, 100_000L, 5_000L))
        r.repo.queued += mirror
        r.flusher.kick()
        assertEquals(listOf("b" to 60_000L), r.repo.pushed.map { it.bookId to it.positionMs })
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a split or the setting off removes queued mirrors at once, even offline`() = runTest {
        val r = rig(online = false)
        r.repo.queued += mirror
        r.repo.queued += PendingProgress("a", "s1", 1_000L, 100_000L, 500L, updatedAtMs = 4_000L)
        r.peers.paired = false
        r.peers.changed.emit(Unit)
        assertEquals(listOf("a"), r.repo.queued.map { it.bookId }) // the copy's own position stays
        assertTrue(r.repo.pushed.isEmpty())
    }

    @Test
    fun `back online long after, the source's own row goes first and the mirror is still delivered`() = runTest {
        val r = rig(online = false)
        // Listened to A offline: A's own position and B's mirror wait.
        r.repo.progress.value = listOf(ReadingProgress("a", 50_000L, 100_000L, 5_000L))
        val servers = HashMap<String, ServerProgress>()
        r.repo.onFetchServerProgress = { id -> Result.success(servers[id]) }
        // A server stamps a delivery with its own clock, ten minutes after the listening.
        r.repo.onPushProgress = { p -> servers[p.bookId] = ServerProgress(p.positionMs, p.durationMs, false, 5_000L + 10 * 60_000L); Result.success(Unit) }
        r.repo.queued += PendingProgress("a", "s1", 50_000L, 100_000L, 30_000L, updatedAtMs = 5_000L)
        r.repo.queued += mirror

        r.connectivity.online.value = true
        assertEquals(listOf("a", "b"), r.repo.pushed.map { it.bookId })
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a mirror whose server was down is delivered when it is back`() = runTest {
        val r = rig()
        r.repo.progress.value = listOf(ReadingProgress("a", 50_000L, 100_000L, 5_000L))
        var down = true
        r.repo.onPushProgress = { if (down) Result.failure(IllegalStateException("down")) else Result.success(Unit) }
        r.repo.queued += mirror
        r.flusher.kick()
        assertEquals(listOf("b"), r.repo.queued.map { it.bookId })
        down = false
        advanceTimeBy(6_000) // the retry after the first backoff
        assertTrue(r.repo.queued.isEmpty())
        assertEquals(2, r.repo.pushed.size)
    }

    @Test
    fun `a mirror aimed at the book in the player waits, it reports for itself`() = runTest {
        val r = rig()
        r.loaded = "b"
        r.repo.queued += mirror
        r.flusher.kick()
        assertTrue(r.repo.pushed.isEmpty())
        assertEquals(listOf("b"), r.repo.queued.map { it.bookId })
    }

    @Test
    fun `when the copies cannot be looked up, no queued mirror is dropped or sent`() = runTest {
        val r = rig()
        r.peers.broken = true
        r.repo.queued += mirror
        r.peers.changed.emit(Unit)
        r.flusher.kick()
        assertTrue(r.repo.pushed.isEmpty())
        assertEquals(listOf("b"), r.repo.queued.map { it.bookId })
    }

    @Test
    fun `a queued mirror is dropped when the copy was listened to here later`() = runTest {
        val r = rig()
        r.repo.progress.value = listOf(ReadingProgress("b", 1_000L, 120_000L, 6_000L))
        r.repo.queued += mirror
        r.flusher.kick()
        assertTrue(r.repo.pushed.isEmpty())
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a queued mirror is dropped when the source copy's server has newer listening from elsewhere`() = runTest {
        val r = rig()
        r.repo.progress.value = listOf(ReadingProgress("a", 50_000L, 100_000L, 5_000L))
        r.repo.onFetchServerProgress = { id ->
            Result.success(if (id == "a") ServerProgress(10_000L, 100_000L, false, 5_000L + 10 * 60_000L) else null)
        }
        r.repo.queued += mirror
        r.flusher.kick()
        assertTrue(r.repo.pushed.isEmpty())
        assertTrue(r.repo.queued.isEmpty())
    }
}
