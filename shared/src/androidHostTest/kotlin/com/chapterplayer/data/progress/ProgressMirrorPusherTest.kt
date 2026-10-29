package com.chapterplayer.data.progress

import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.sync.ServerStatusRecord
import com.chapterplayer.data.sync.ServerStatusStore
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The other copy's server hears where listening is, through the pending queue, throttled. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressMirrorPusherTest {

    private class Peers : ProgressMirrorPeers {
        var groups: Map<String, List<MirrorPeer>> = mapOf(
            "a" to listOf(MirrorPeer("plex_a", "plex_srv", 120_000L)),
            "plex_a" to listOf(MirrorPeer("a", "s1", 100_000L)),
        )
        override suspend fun peersOf(bookId: String): List<MirrorPeer> = groups[bookId].orEmpty()
    }

    private class Status : ServerStatusStore {
        val records = MutableStateFlow<Map<String, ServerStatusRecord>>(emptyMap())
        override val all: Flow<Map<String, ServerStatusRecord>> = records
        override suspend fun get(serverId: String): ServerStatusRecord = records.value[serverId] ?: ServerStatusRecord()
        override suspend fun update(serverId: String, transform: (ServerStatusRecord) -> ServerStatusRecord) {
            records.value = records.value + (serverId to transform(get(serverId)))
        }
        override suspend fun remove(serverId: String) { records.value = records.value - serverId }
    }

    private class Rig(scope: CoroutineScope, now: () -> Long) {
        val controller = FakePlaybackController()
        val repo = FakeBookRepository(listOf(testBook("a"), testBook("plex_a")))
        val peers = Peers()
        val status = Status()
        var kicks = 0
        val pusher = ProgressMirrorPusher(controller, repo, peers, status, scope, now, onQueued = { kicks++ }).also { it.start() }
    }

    private fun TestScope.rig() = Rig(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        { testScheduler.currentTime }
    )

    private fun Rig.play(book: String = "a", pos: Long, playing: Boolean = true) {
        controller.playback.value = PlaybackState(bookId = book, isPlaying = playing, positionMs = pos, durationMs = 100_000L)
    }

    @Test
    fun `while playing the other copy's server hears the mapped position once a minute`() = runTest {
        val r = rig()
        r.play(pos = 10_000)
        advanceTimeBy(59_500)
        assertTrue(r.repo.queueCalls.isEmpty())
        r.play(pos = 50_000)
        advanceTimeBy(1_000)
        val first = r.repo.queueCalls.single()
        assertEquals(PendingProgress("plex_a", "plex_srv", 60_000L, 120_000L, 0L, updatedAtMs = first.updatedAtMs), first)
        assertEquals(1, r.kicks)
        // Not again within the minute.
        r.play(pos = 60_000)
        advanceTimeBy(30_000)
        assertEquals(1, r.repo.queueCalls.size)
        advanceTimeBy(31_000)
        assertEquals(2, r.repo.queueCalls.size)
        assertEquals(72_000L, r.repo.queueCalls.last().positionMs)
    }

    @Test
    fun `pausing sends the position at once, but the same position only once`() = runTest {
        val r = rig()
        r.play(pos = 10_000)
        advanceTimeBy(5_000)
        r.play(pos = 25_000, playing = false)
        assertEquals(listOf(30_000L), r.repo.queueCalls.map { it.positionMs })
        r.play(pos = 25_000, playing = true)
        r.play(pos = 25_000, playing = false)
        assertEquals(1, r.repo.queueCalls.size)
    }

    @Test
    fun `switching to the other copy pushes nothing to it, it reports for itself`() = runTest {
        val r = rig()
        r.play("a", pos = 10_000)
        advanceTimeBy(5_000)
        r.play("plex_a", pos = 12_000)
        assertTrue(r.repo.queueCalls.isEmpty())
    }

    @Test
    fun `a server whose last sync failed gets no push`() = runTest {
        val r = rig()
        r.status.records.value = mapOf("plex_srv" to ServerStatusRecord(lastSuccessAtMs = 1L, lastErrorAtMs = 2L))
        r.play(pos = 10_000)
        advanceTimeBy(5_000)
        r.play(pos = 20_000, playing = false)
        assertTrue(r.repo.queueCalls.isEmpty())
    }

    @Test
    fun `a split pair or the setting off pushes nothing`() = runTest {
        val r = rig()
        r.peers.groups = emptyMap()
        r.play(pos = 10_000)
        advanceTimeBy(120_000)
        r.play(pos = 20_000, playing = false)
        assertTrue(r.repo.queueCalls.isEmpty())
    }

    @Test
    fun `offline the push waits in the queue and the flusher delivers it when the network returns`() = runTest {
        val r = rig()
        val connectivity = FakeConnectivity(online = false)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val flusher = PendingProgressFlusher(r.repo, connectivity, scope, { testScheduler.currentTime }).also { it.start() }
        r.play(pos = 10_000)
        advanceTimeBy(5_000)
        r.play(pos = 50_000, playing = false)
        flusher.kick()
        assertTrue(r.repo.pushed.isEmpty())
        assertEquals(listOf("plex_a"), r.repo.queued.map { it.bookId })

        connectivity.online.value = true
        assertEquals(listOf("plex_a" to 60_000L), r.repo.pushed.map { it.bookId to it.positionMs })
        assertTrue(r.repo.queued.isEmpty())
    }

    @Test
    fun `a queued push yields to newer listening on the other copy's server`() = runTest {
        val r = rig()
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        r.repo.onFetchServerProgress = { id -> Result.success(if (id == "plex_a") ServerProgress(1_000L, 120_000L, false, Long.MAX_VALUE) else null) }
        val flusher = PendingProgressFlusher(r.repo, FakeConnectivity(online = true), scope, { testScheduler.currentTime }).also { it.start() }
        r.play(pos = 10_000)
        advanceTimeBy(5_000)
        r.play(pos = 50_000, playing = false)
        flusher.kick()
        assertTrue(r.repo.pushed.isEmpty())
        assertEquals(listOf("plex_a"), r.repo.savedServer.map { it.first })
    }
}
