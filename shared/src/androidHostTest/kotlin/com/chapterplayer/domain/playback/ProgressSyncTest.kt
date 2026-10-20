package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressSyncTest {

    private fun local(positionMs: Long, lastPlayedAt: Long, finished: Boolean = false, durationMs: Long = 1_000_000) =
        ReadingProgress("a", positionMs, durationMs, lastPlayedAt, finished)

    private fun server(positionMs: Long, updatedAt: Long, finished: Boolean = false, durationMs: Long = 1_000_000) =
        ServerProgress(positionMs, durationMs, finished, updatedAt)

    // ---- chooseStartPosition ----

    @Test
    fun `no progress anywhere gives no start position`() {
        assertNull(chooseStartPosition(null, null))
    }

    @Test
    fun `only local progress gives its position, only server progress gives its position`() {
        assertEquals(120_000, chooseStartPosition(local(120_000, 10), null))
        assertEquals(300_000, chooseStartPosition(null, server(300_000, 10)))
    }

    @Test
    fun `finished books and books near the end start from the beginning`() {
        assertEquals(0, chooseStartPosition(local(500_000, 10, finished = true), null))
        assertEquals(0, chooseStartPosition(null, server(500_000, 10, finished = true)))
        assertEquals(0, chooseStartPosition(local(995_000, 10), null))
        assertEquals(0, chooseStartPosition(null, server(999_000, 10)))
        assertEquals(994_999, chooseStartPosition(local(994_999, 10), null))
    }

    @Test
    fun `with both the more recent one wins`() {
        assertEquals(300_000, chooseStartPosition(local(120_000, 100), server(300_000, 200)))
        assertEquals(120_000, chooseStartPosition(local(120_000, 300), server(300_000, 200)))
    }

    @Test
    fun `a tie or a missing server timestamp prefers the local position`() {
        assertEquals(120_000, chooseStartPosition(local(120_000, 200), server(300_000, 200)))
        assertEquals(120_000, chooseStartPosition(local(120_000, 0), server(300_000, 0)))
        assertEquals(120_000, chooseStartPosition(local(120_000, -5), server(300_000, -1)))
    }

    @Test
    fun `the newer one decides even when it is finished`() {
        assertEquals(0, chooseStartPosition(local(120_000, 100), server(999_999, 200, finished = true)))
        assertEquals(120_000, chooseStartPosition(local(120_000, 300), server(999_999, 200, finished = true)))
    }

    // ---- save and sync timing ----

    @Test
    fun `saving and syncing happen only while playing and once the interval has passed`() {
        assertFalse(shouldSaveNow(0, 20_000, isPlaying = false))
        assertFalse(shouldSaveNow(0, 9_999, isPlaying = true))
        assertTrue(shouldSaveNow(0, 10_000, isPlaying = true))
        assertTrue(shouldSaveNow(5_000, 20_000, isPlaying = true))
        assertFalse(shouldSyncNow(0, 20_000, isPlaying = false))
        assertFalse(shouldSyncNow(0, 9_999, isPlaying = true))
        assertTrue(shouldSyncNow(0, 10_000, isPlaying = true))
    }

    @Test
    fun `a custom interval is respected and a clock that went backwards saves`() {
        assertFalse(shouldSaveNow(1_000, 5_999, isPlaying = true, intervalMs = 5_000))
        assertTrue(shouldSaveNow(1_000, 6_000, isPlaying = true, intervalMs = 5_000))
        assertTrue(shouldSaveNow(50_000, 40_000, isPlaying = true))
        assertTrue(shouldSyncNow(50_000, 40_000, isPlaying = true))
    }

    // ---- backoff ----

    @Test
    fun `backoff grows then stays at five minutes`() {
        assertEquals(0, nextBackoffMs(0))
        assertEquals(0, nextBackoffMs(-3))
        assertEquals(5_000, nextBackoffMs(1))
        assertEquals(10_000, nextBackoffMs(2))
        assertEquals(20_000, nextBackoffMs(3))
        assertEquals(40_000, nextBackoffMs(4))
        assertEquals(300_000, nextBackoffMs(5))
        assertEquals(300_000, nextBackoffMs(50))
    }

    // ---- queue coalescing ----

    private fun pending(position: Long, updatedAt: Long, listened: Long = 0, attempts: Int = 0, duration: Long = 1_000_000) =
        PendingProgress("a", "s1", position, duration, listened, updatedAt, attempts)

    @Test
    fun `the first queued row is kept as it is`() {
        val row = pending(1_000, 10)
        assertEquals(row, coalescePending(null, row))
    }

    @Test
    fun `a newer position replaces the old one, listened time adds up and attempts reset`() {
        val merged = coalescePending(pending(1_000, 10, listened = 5_000, attempts = 3), pending(2_000, 20, listened = 7_000, duration = 2_000_000))
        assertEquals(pending(2_000, 20, listened = 12_000, attempts = 0, duration = 2_000_000), merged)
    }

    @Test
    fun `an older incoming position keeps the existing one but still adds its listened time`() {
        val merged = coalescePending(pending(2_000, 20, listened = 5_000, attempts = 2), pending(1_000, 10, listened = 1_000))
        assertEquals(pending(2_000, 20, listened = 6_000, attempts = 2), merged)
    }

    // ---- flush decision ----

    @Test
    fun `queued progress is sent when the server has nothing newer`() {
        assertEquals(FlushDecision.SEND, flushDecision(pending(1_000, 100), null))
        assertEquals(FlushDecision.SEND, flushDecision(pending(1_000, 100), server(5_000, 50)))
        assertEquals(FlushDecision.SEND, flushDecision(pending(1_000, 100), server(5_000, 100)))
    }

    @Test
    fun `queued progress is dropped when the server is strictly newer`() {
        assertEquals(FlushDecision.DROP_SERVER_NEWER, flushDecision(pending(1_000, 100), server(5_000, 101)))
    }

    // ---- merging server progress into local progress ----

    @Test
    fun `server progress becomes local progress when there is none`() {
        val merged = mergeServerProgress("a", null, server(300_000, 200, finished = false), hasPendingLocal = false)
        assertEquals(ReadingProgress("a", 300_000, 1_000_000, 200, false), merged)
    }

    @Test
    fun `a newer server row replaces an older local one and carries the finished flag`() {
        val merged = mergeServerProgress("a", local(100_000, 100), server(999_999, 200, finished = true), hasPendingLocal = false)
        assertEquals(ReadingProgress("a", 999_999, 1_000_000, 200, true), merged)
    }

    @Test
    fun `nothing changes when local is as new or newer, or a local update is waiting to be sent`() {
        assertNull(mergeServerProgress("a", local(100_000, 300), server(999_999, 200), hasPendingLocal = false))
        assertNull(mergeServerProgress("a", local(100_000, 200), server(999_999, 200), hasPendingLocal = false))
        assertNull(mergeServerProgress("a", local(100_000, 100), server(999_999, 200), hasPendingLocal = true))
        assertNull(mergeServerProgress("a", null, server(999_999, 200), hasPendingLocal = true))
    }
}
