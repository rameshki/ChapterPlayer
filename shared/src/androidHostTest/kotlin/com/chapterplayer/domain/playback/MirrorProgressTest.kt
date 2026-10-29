package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.sync.ServerStatusRecord
import com.chapterplayer.domain.browse.MergeOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorProgressTest {

    private val hour = 3_600_000L

    private fun progress(
        id: String = "a", pos: Long, dur: Long = 10 * hour, at: Long = 1_000L, finished: Boolean = false, mirrored: Boolean = false,
    ) = ReadingProgress(id, pos, dur, at, finished, mirrored)

    // ---- mapping by fraction ----

    @Test
    fun `positions map by fraction of the whole book, in whole seconds`() {
        data class Case(val pos: Long, val from: Long, val to: Long, val expected: Long?)
        val cases = listOf(
            Case(5 * hour, 10 * hour, 12 * hour, 6 * hour), // half way stays half way
            Case(0L, 10 * hour, 12 * hour, 0L),
            Case(1_234L, 10_000L, 10_000L, 1_000L), // rounded to the second
            Case(1_600L, 10_000L, 10_000L, 2_000L),
            Case(10 * hour, 10 * hour, 9 * hour, 9 * hour), // the end maps to the end
            Case(11 * hour, 10 * hour, 9 * hour, 9 * hour), // never past the end
            Case(9_800L, 10_000L, 9_700L, 9_700L), // rounding up never passes an end that is not a whole second
            Case(-5L, 10_000L, 10_000L, 0L),
            Case(5_000L, 0L, 10_000L, null), // an unknown length cannot be mapped
            Case(5_000L, 10_000L, 0L, null),
            Case(5_000L, -1L, 10_000L, null),
        )
        for (c in cases) assertEquals(c.expected, mirrorPosition(c.pos, c.from, c.to), "$c")
    }

    // ---- the local mirror of one copy into another ----

    @Test
    fun `a copy follows the other copy's position, time and finished state, flagged as mirrored`() {
        val source = progress(pos = 5 * hour, at = 2_000L)
        val mirror = mirroredProgress(source, "b", targetDurationMs = 12 * hour, existing = null)
        assertEquals(ReadingProgress("b", 6 * hour, 12 * hour, 2_000L, isFinished = false, mirrored = true), mirror)
    }

    @Test
    fun `a zero or unknown length mirrors nothing, except a restart at the beginning`() {
        assertNull(mirroredProgress(progress(pos = 5 * hour), "b", targetDurationMs = 0L, existing = null))
        assertNull(mirroredProgress(progress(pos = 5 * hour, dur = 0L), "b", targetDurationMs = 12 * hour, existing = null))
        // A reset maps to 0 whatever the lengths are.
        assertEquals(
            ReadingProgress("b", 0L, 0L, 1_000L, isFinished = false, mirrored = true),
            mirroredProgress(progress(pos = 0L, dur = 0L), "b", targetDurationMs = 0L, existing = null)
        )
    }

    @Test
    fun `an unknown target length falls back to the target's stored length`() {
        val existing = progress(id = "b", pos = 0L, dur = 12 * hour, at = 1L, mirrored = true)
        assertEquals(6 * hour, mirroredProgress(progress(pos = 5 * hour, at = 9L), "b", 0L, existing)?.positionMs)
    }

    @Test
    fun `a finished copy mirrors as finished, an un-finished one at zero`() {
        val finished = mirroredProgress(progress(pos = 4 * hour, at = 5L, finished = true), "b", 12 * hour, existing = null)
        assertEquals(true, finished?.isFinished)
        assertEquals(true, finished?.mirrored)
        // A finished copy without a known length still marks the other copy finished, at its end.
        val noLength = mirroredProgress(progress(pos = 0L, dur = 0L, at = 5L, finished = true), "b", 12 * hour, existing = null)
        assertEquals(ReadingProgress("b", 12 * hour, 12 * hour, 5L, isFinished = true, mirrored = true), noLength)
        val existing = progress(id = "b", pos = 12 * hour, dur = 12 * hour, at = 3L, finished = true)
        val reset = mirroredProgress(progress(pos = 0L, at = 5L), "b", 12 * hour, existing)
        assertEquals(ReadingProgress("b", 0L, 12 * hour, 5L, isFinished = false, mirrored = true), reset)
    }

    @Test
    fun `newest wins - a copy with newer progress of its own is never overwritten`() {
        val newer = progress(id = "b", pos = 1 * hour, dur = 12 * hour, at = 5_000L)
        assertNull(mirroredProgress(progress(pos = 5 * hour, at = 4_000L), "b", 12 * hour, newer))
        // A newer mirrored row (from a third copy) is not overwritten by older listening either.
        assertNull(mirroredProgress(progress(pos = 5 * hour, at = 4_000L), "b", 12 * hour, newer.copy(mirrored = true)))
        // A tie keeps the copy's own listening.
        assertNull(mirroredProgress(progress(pos = 5 * hour, at = 5_000L), "b", 12 * hour, newer))
    }

    @Test
    fun `an older row of the copy is overwritten`() {
        val older = progress(id = "b", pos = 1 * hour, dur = 12 * hour, at = 1_000L)
        assertEquals(6 * hour, mirroredProgress(progress(pos = 5 * hour, at = 4_000L), "b", 12 * hour, older)?.positionMs)
    }

    @Test
    fun `a mirrored row is never mirrored on`() {
        assertNull(mirroredProgress(progress(pos = 5 * hour, at = 9L, mirrored = true), "b", 12 * hour, existing = null))
        assertNull(mirroredProgress(progress(pos = 5 * hour, at = 9L), "a", 10 * hour, existing = null))
    }

    @Test
    fun `an echo of a copy's own position does not replace its own row, a mirrored row follows every save`() {
        // The copy's own row is at about the mapped position already: it stays its own.
        val own = progress(id = "b", pos = 6 * hour - 10_000L, dur = 12 * hour, at = 1_000L)
        assertNull(mirroredProgress(progress(pos = 5 * hour, at = 4_000L), "b", 12 * hour, own))
        // A mirrored row 10 s behind moves on.
        val mirrored = own.copy(mirrored = true)
        assertEquals(6 * hour, mirroredProgress(progress(pos = 5 * hour, at = 4_000L), "b", 12 * hour, mirrored)?.positionMs)
        // The same mirror twice writes nothing the second time.
        val same = ReadingProgress("b", 6 * hour, 12 * hour, 4_000L, isFinished = false, mirrored = true)
        assertNull(mirroredProgress(progress(pos = 5 * hour, at = 4_000L), "b", 12 * hour, same))
    }

    // ---- which copies belong together ----

    private fun book(id: String, server: String, title: String = "The Book", author: String = "Ann Author", dur: Long = 10 * hour,
                     source: ServerSource = ServerSource.ABS) =
        AudioBook(id, title, author, "", dur, emptyList(), "", null, source, server)

    @Test
    fun `the copies of a merged book are each other's peers, a book on one server has none`() {
        val books = listOf(
            book("abs_1", "s1"), book("plex_1", "s2", dur = 10 * hour + 60_000L, source = ServerSource.PLEX),
            book("abs_2", "s1", title = "Other"),
        )
        val groups = mirrorGroups(books, MergeOverrides())
        assertEquals(listOf("plex_1"), groups["abs_1"])
        assertEquals(listOf("abs_1"), groups["plex_1"])
        assertNull(groups["abs_2"])
    }

    @Test
    fun `a split pair is not mirrored, a manual merge is`() {
        val books = listOf(book("abs_1", "s1"), book("plex_1", "s2", source = ServerSource.PLEX), book("abs_9", "s3", title = "Different"))
        val split = mirrorGroups(books, MergeOverrides(splits = setOf("abs_1" to "plex_1")))
        assertNull(split["abs_1"])
        assertNull(split["plex_1"])
        val merged = mirrorGroups(books, MergeOverrides(merges = setOf("abs_9" to "abs_1"), splits = setOf("abs_1" to "plex_1")))
        assertEquals(listOf("abs_9"), merged["abs_1"])
    }

    @Test
    fun `three copies each list the other two`() {
        val books = listOf(book("a", "s1"), book("b", "s2"), book("c", "s3"))
        assertEquals(listOf("b", "c"), mirrorGroups(books, MergeOverrides())["a"])
        assertEquals(listOf("a", "c"), mirrorGroups(books, MergeOverrides())["b"])
    }

    // ---- server pushes: throttle and targets ----

    @Test
    fun `a push to the other copy is due once a minute while playing, and always on pause or stop`() {
        assertTrue(mirrorPushDue(lastPushedAtMs = null, nowMs = 0L, isPlaying = true))
        assertFalse(mirrorPushDue(lastPushedAtMs = 1_000L, nowMs = 60_999L, isPlaying = true))
        assertTrue(mirrorPushDue(lastPushedAtMs = 1_000L, nowMs = 61_000L, isPlaying = true))
        assertTrue(mirrorPushDue(lastPushedAtMs = 1_000L, nowMs = 2_000L, isPlaying = false))
        // A clock that went back does not block pushes for good.
        assertTrue(mirrorPushDue(lastPushedAtMs = 100_000L, nowMs = 1_000L, isPlaying = true))
    }

    @Test
    fun `pushes skip the loaded copy and servers whose last sync failed`() {
        val peers = listOf(MirrorPeer("b", "s2", 1L), MirrorPeer("c", "s3", 1L), MirrorPeer("d", "s4", 1L))
        assertEquals(listOf("c"), mirrorPushTargets(peers, loadedBookId = "b", unreachableServerIds = setOf("s4")).map { it.bookId })
        assertEquals(listOf("b", "c", "d"), mirrorPushTargets(peers, loadedBookId = null, unreachableServerIds = emptySet()).map { it.bookId })
    }

    @Test
    fun `a server is unreachable when its last error is newer than its last success`() {
        val records = mapOf(
            "ok" to ServerStatusRecord(lastSuccessAtMs = 5L, lastErrorAtMs = 4L),
            "failing" to ServerStatusRecord(lastSuccessAtMs = 4L, lastErrorAtMs = 5L),
            "never" to ServerStatusRecord(lastErrorAtMs = 5L),
            "fresh" to ServerStatusRecord(),
        )
        assertEquals(setOf("failing", "never"), unreachableServerIds(records))
    }

    // ---- the start position across copies ----

    @Test
    fun `another copy's progress maps onto this copy by fraction`() {
        assertEquals(
            ServerProgress(6 * hour, 12 * hour, false, 7L),
            progressOnCopy(ServerProgress(5 * hour, 10 * hour, false, 7L), 12 * hour)
        )
        assertEquals(ServerProgress(12 * hour, 12 * hour, true, 7L), progressOnCopy(ServerProgress(0L, 0L, true, 7L), 12 * hour))
        assertNull(progressOnCopy(ServerProgress(5 * hour, 0L, false, 7L), 12 * hour))
        assertNull(progressOnCopy(ServerProgress(5 * hour, 10 * hour, false, 7L), 0L))
    }

    @Test
    fun `the newest of this copy's server and the other copies wins, ties go to this copy`() {
        val own = ServerProgress(1L, 10L, false, 100L)
        val older = ServerProgress(2L, 10L, false, 50L)
        val newer = ServerProgress(3L, 10L, false, 200L)
        assertEquals(newer, newestAcrossCopies(own, listOf(older, newer)))
        assertEquals(own, newestAcrossCopies(own, listOf(own.copy(positionMs = 9L))))
        assertEquals(newer, newestAcrossCopies(null, listOf(newer, older)))
        assertNull(newestAcrossCopies(null, emptyList()))
        // Another copy's progress without a time cannot be weighed: ignored.
        assertEquals(own, newestAcrossCopies(own, listOf(ServerProgress(5L, 10L, false, 0L))))
    }

    @Test
    fun `a mark on several copies reports queued when any waits, synced when any reached a server`() {
        assertEquals(MarkOutcome.QUEUED, combinedMarkOutcome(listOf(MarkOutcome.SYNCED, MarkOutcome.QUEUED)))
        assertEquals(MarkOutcome.SYNCED, combinedMarkOutcome(listOf(MarkOutcome.LOCAL_ONLY, MarkOutcome.SYNCED)))
        assertEquals(MarkOutcome.LOCAL_ONLY, combinedMarkOutcome(listOf(MarkOutcome.LOCAL_ONLY)))
    }
}
