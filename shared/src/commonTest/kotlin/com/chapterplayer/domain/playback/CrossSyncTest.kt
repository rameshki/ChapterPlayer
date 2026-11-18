package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrossSyncTest {
    private fun row(id: String, pos: Long, at: Long, finished: Boolean = false, mirrored: Boolean = false, duration: Long = 100_000L) =
        ReadingProgress(id, pos, duration, at, finished, mirrored)

    @Test
    fun `the newest listening is the source`() {
        val rows = listOf(row("abs", 10_000, at = 100), row("jf", 40_000, at = 300), row("plex", 20_000, at = 200))
        assertEquals("jf", newestCopyProgress(rows)?.bookId)
    }

    @Test
    fun `a copy that was never listened to is not a source`() {
        assertNull(newestCopyProgress(listOf(row("abs", 0, at = 500), row("jf", 0, at = 900))))
        assertNull(newestCopyProgress(emptyList()))
    }

    @Test
    fun `a finished book is a source even at zero`() {
        assertEquals("jf", newestCopyProgress(listOf(row("abs", 10_000, at = 100), row("jf", 0, at = 300, finished = true)))?.bookId)
    }

    @Test
    fun `on the same time a copy's own row beats a mirror`() {
        val mirror = row("abs", 30_000, at = 300, mirrored = true)
        val own = row("jf", 30_000, at = 300)
        assertEquals("jf", newestCopyProgress(listOf(mirror, own))?.bookId)
        assertEquals("jf", newestCopyProgress(listOf(own, mirror))?.bookId)
    }

    @Test
    fun `on the same time and the same kind the first one stays`() {
        assertEquals("abs", newestCopyProgress(listOf(row("abs", 30_000, at = 300), row("jf", 31_000, at = 300)))?.bookId)
    }

    @Test
    fun `the push carries the mapped place, the source's time and no listened time`() {
        val source = row("jf", 40_000, at = 300, duration = 100_000)
        val peer = MirrorPeer("abs", "srv_abs", 200_000)
        val mirror = mirroredProgress(source, peer.bookId, peer.durationMs, null)!!
        val pending = crossSyncPending(source, peer, mirror)
        assertEquals("abs", pending.bookId)
        assertEquals("srv_abs", pending.serverId)
        assertEquals(80_000L, pending.positionMs)
        assertEquals(200_000L, pending.durationMs)
        assertEquals(300L, pending.updatedAtMs)
        assertEquals(0L, pending.listenedMs)
        assertEquals("jf", pending.mirrorOf)
        assertNull(pending.markedFinished)
    }

    @Test
    fun `a finished source is pushed as finished`() {
        val source = row("jf", 100_000, at = 300, finished = true)
        val peer = MirrorPeer("abs", "srv_abs", 100_000)
        val mirror = mirroredProgress(source, peer.bookId, peer.durationMs, null)!!
        assertEquals(true, crossSyncPending(source, peer, mirror).markedFinished)
    }

    @Test
    fun `the result says what happened in words`() {
        assertEquals("No book is on more than one server.", CopySyncResult(0, 0).text)
        assertEquals("Every copy was already up to date (4 books on more than one server).", CopySyncResult(4, 0).text)
        assertEquals("Brought 1 copy up to date.", CopySyncResult(4, 1).text)
        assertEquals("Brought 7 copies up to date.", CopySyncResult(9, 7).text)
    }
}
