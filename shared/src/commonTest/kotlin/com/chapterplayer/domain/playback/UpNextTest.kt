package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpNextTest {

    private fun item(
        id: String,
        serverId: String = "abs1",
        downloaded: Boolean = false,
        lastPlayedAt: Long? = null,
    ) = BrowseItem(
        AudioBook(
            id = id, title = "Book $id", author = "Someone", narrator = "", durationMs = 60_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null,
            source = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS, serverId = serverId,
        ),
        lastPlayedAt?.let { ReadingProgress(id, 1_000L, 60_000L, it) },
        if (downloaded) DownloadState.DOWNLOADED else DownloadState.NOT_DOWNLOADED,
    )

    private fun merged(primary: BrowseItem, vararg others: BrowseItem) = primary.copy(sources = listOf(primary) + others)

    private val anyCopy: (BrowseItem) -> Boolean = { true }

    // -- Resolving ---------------------------------------------------------------------------

    @Test
    fun `rows resolve to their entries in queue order`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val resolved = resolveUpNext(listOf("c", "a"), items)
        assertEquals(listOf("c", "a"), resolved.entries.map { it.entry.book.id })
        assertEquals(emptyList(), resolved.stale)
    }

    @Test
    fun `a row of a book that is gone is stale`() {
        val resolved = resolveUpNext(listOf("gone", "a"), listOf(item("a")))
        assertEquals(listOf("a"), resolved.entries.map { it.queueId })
        assertEquals(listOf("gone"), resolved.stale)
    }

    @Test
    fun `a row of another copy finds the merged entry`() {
        val entry = merged(item("a"), item("p", "plex1"))
        val resolved = resolveUpNext(listOf("p"), listOf(entry))
        assertEquals("p", resolved.entries.single().queueId)
        assertEquals("a", resolved.entries.single().entry.book.id)
    }

    @Test
    fun `two rows of one entry keep the first and the second is stale`() {
        val entry = merged(item("a"), item("p", "plex1"))
        val resolved = resolveUpNext(listOf("p", "b", "a"), listOf(entry, item("b")))
        assertEquals(listOf("p", "b"), resolved.entries.map { it.queueId })
        assertEquals(listOf("a"), resolved.stale)
    }

    // -- Play next and Add to Up next ----------------------------------------------------------

    @Test
    fun `play next puts the book at the front`() {
        val edit = editUpNext(listOf("x", "y"), listOf("a"), loadedBookId = null, front = true)
        assertEquals(listOf("a", "x", "y"), edit.queue)
        assertEquals(UpNextOutcome.PLAYS_NEXT, edit.outcome)
    }

    @Test
    fun `play next of a queued book moves it to the front`() {
        val edit = editUpNext(listOf("x", "a", "y"), listOf("a"), loadedBookId = null, front = true)
        assertEquals(listOf("a", "x", "y"), edit.queue)
        assertEquals(UpNextOutcome.MOVED_TO_FRONT, edit.outcome)
    }

    @Test
    fun `play next of a merged book replaces the row of its other copy`() {
        val edit = editUpNext(listOf("x", "p"), listOf("a", "p"), loadedBookId = null, front = true)
        assertEquals(listOf("a", "x"), edit.queue)
        assertEquals(UpNextOutcome.MOVED_TO_FRONT, edit.outcome)
    }

    @Test
    fun `add puts the book at the end`() {
        val edit = editUpNext(listOf("x"), listOf("a"), loadedBookId = null, front = false)
        assertEquals(listOf("x", "a"), edit.queue)
        assertEquals(UpNextOutcome.ADDED, edit.outcome)
    }

    @Test
    fun `add of a queued book keeps its place`() {
        val edit = editUpNext(listOf("p", "x"), listOf("a", "p"), loadedBookId = null, front = false)
        assertEquals(listOf("p", "x"), edit.queue)
        assertEquals(UpNextOutcome.ALREADY_QUEUED, edit.outcome)
    }

    @Test
    fun `the book in the player is never queued`() {
        val front = editUpNext(listOf("x"), listOf("a", "p"), loadedBookId = "p", front = true)
        assertEquals(listOf("x"), front.queue)
        assertEquals(UpNextOutcome.PLAYING_NOW, front.outcome)
        val end = editUpNext(listOf("x"), listOf("a"), loadedBookId = "a", front = false)
        assertEquals(listOf("x"), end.queue)
        assertEquals(UpNextOutcome.PLAYING_NOW, end.outcome)
    }

    @Test
    fun `removing drops every row of the entry`() {
        assertEquals(listOf("x"), removeFromUpNext(listOf("a", "x", "p"), listOf("a", "p")))
    }

    @Test
    fun `moving stops at either end`() {
        val queue = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moveInUpNext(queue, "b", -1))
        assertEquals(listOf("a", "c", "b"), moveInUpNext(queue, "b", 1))
        assertEquals(queue, moveInUpNext(queue, "a", -1))
        assertEquals(queue, moveInUpNext(queue, "c", 1))
        assertEquals(queue, moveInUpNext(queue, "zz", 1))
    }

    @Test
    fun `every outcome has words`() {
        for (outcome in UpNextOutcome.entries) assertTrue(upNextOutcomeText(outcome).isNotBlank())
    }

    // -- Which copy can play ------------------------------------------------------------------

    @Test
    fun `a downloaded copy can play offline and with downloaded only`() {
        assertTrue(upNextCanPlay(item("a", downloaded = true), online = false, downloadedOnly = true, reachableServerIds = emptySet()))
    }

    @Test
    fun `a streamed copy needs the network and its server`() {
        val a = item("a")
        assertTrue(upNextCanPlay(a, online = true, downloadedOnly = false, reachableServerIds = null))
        assertTrue(upNextCanPlay(a, online = true, downloadedOnly = false, reachableServerIds = setOf("abs1")))
        assertFalse(upNextCanPlay(a, online = true, downloadedOnly = false, reachableServerIds = setOf("plex1")))
        assertFalse(upNextCanPlay(a, online = false, downloadedOnly = false, reachableServerIds = null))
        assertFalse(upNextCanPlay(a, online = true, downloadedOnly = true, reachableServerIds = null))
    }

    // -- Planning the next book ---------------------------------------------------------------

    @Test
    fun `the first entry starts`() {
        val plan = planUpNext(listOf("b", "c"), listOf(item("a"), item("b"), item("c")), endedBookId = "a", canPlay = anyCopy)
        assertEquals(UpNextPick("b", item("b")), plan.start)
        assertEquals(emptyList(), plan.drop)
        assertEquals(emptyList(), plan.skipped)
    }

    @Test
    fun `an empty queue starts nothing`() {
        val plan = planUpNext(emptyList(), listOf(item("a")), endedBookId = "a", canPlay = anyCopy)
        assertNull(plan.start)
    }

    @Test
    fun `stale rows and the ended book are dropped`() {
        val plan = planUpNext(listOf("gone", "a", "b"), listOf(item("a"), item("b")), endedBookId = "a", canPlay = anyCopy)
        assertEquals("b", plan.start?.queueId)
        assertEquals(listOf("gone", "a"), plan.drop)
    }

    @Test
    fun `a book that cannot play now is skipped and the next starts`() {
        val canPlay = { member: BrowseItem -> member.book.id != "b" }
        val plan = planUpNext(listOf("b", "c"), listOf(item("b"), item("c")), endedBookId = "a", canPlay = canPlay)
        assertEquals("c", plan.start?.queueId)
        assertEquals(listOf("b"), plan.skipped.map { it.queueId })
        // A skipped book stays queued.
        assertFalse("b" in plan.drop)
    }

    @Test
    fun `when nothing can play nothing starts and every entry is skipped`() {
        val plan = planUpNext(listOf("b", "c"), listOf(item("b"), item("c")), endedBookId = "a", canPlay = { false })
        assertNull(plan.start)
        assertEquals(listOf("b", "c"), plan.skipped.map { it.queueId })
    }

    @Test
    fun `a merged book plays its downloaded copy first`() {
        val abs = item("a", lastPlayedAt = 9L)
        val plex = item("p", "plex1", downloaded = true)
        val plan = planUpNext(listOf("a"), listOf(merged(abs, plex)), endedBookId = "x", canPlay = anyCopy)
        assertEquals("p", plan.start?.copy?.book?.id)
    }

    @Test
    fun `a merged book plays the copy played last when none is downloaded`() {
        val abs = item("a")
        val plex = item("p", "plex1", lastPlayedAt = 9L)
        val entry = merged(abs, plex).copy(progress = plex.progress)
        val plan = planUpNext(listOf("a"), listOf(entry), endedBookId = "x", canPlay = anyCopy)
        assertEquals("p", plan.start?.copy?.book?.id)
    }

    @Test
    fun `a merged book whose last played server is away plays the copy that can`() {
        val abs = item("a")
        val plex = item("p", "plex1", lastPlayedAt = 9L)
        val entry = merged(abs, plex).copy(progress = plex.progress)
        val plan = planUpNext(listOf("p"), listOf(entry), endedBookId = "x", canPlay = { it.book.serverId == "abs1" })
        assertEquals("a", plan.start?.copy?.book?.id)
        assertEquals("p", plan.start?.queueId)
    }

    @Test
    fun `the rows of an entry are found through any copy`() {
        val entry = merged(item("a"), item("p", "plex1"))
        assertEquals(listOf("p"), upNextRowsOf(listOf("x", "p"), listOf(entry, item("x")), "a"))
        assertEquals(listOf("p"), upNextRowsOf(listOf("x", "p"), listOf(entry, item("x")), "p"))
        assertEquals(emptyList(), upNextRowsOf(listOf("x"), listOf(entry, item("x")), "a"))
    }

    @Test
    fun `the skip note names one book and counts several`() {
        assertNull(upNextSkipText(emptyList(), started = true))
        assertEquals("Up next skipped \"Book b\": not playable now", upNextSkipText(listOf("Book b"), started = true))
        assertEquals("Up next: 2 books cannot play now (needs a connection)", upNextSkipText(listOf("x", "y"), started = false))
    }

    @Test
    fun `the start failure note carries the reason`() {
        assertEquals("Up next could not start the next book. Needs a connection to play", upNextStartFailedText("Needs a connection to play"))
    }

    // -- When a book ends ---------------------------------------------------------------------

    private fun playing(position: Long = 59_500L, sleep: Long? = null, speed: Float = 1f) = PlaybackState(
        bookId = "a", isPlaying = true, playWhenReady = true, positionMs = position, durationMs = 60_000L, sleepRemainingMs = sleep, speed = speed,
    )

    private val ended = PlaybackState(bookId = "a", positionMs = 60_000L, durationMs = 60_000L, ended = true, playWhenReady = true)

    @Test
    fun `the end reached while playing moves on`() {
        assertTrue(endedWhilePlaying(playing(), ended))
    }

    @Test
    fun `the end reached while buffering to play moves on`() {
        val buffering = playing().copy(isPlaying = false, isBuffering = true)
        assertTrue(endedWhilePlaying(buffering, ended))
    }

    @Test
    fun `the iOS end which clears play when ready still counts`() {
        assertTrue(endedWhilePlaying(playing(), ended.copy(playWhenReady = false)))
    }

    @Test
    fun `a pause just before the end does not move on`() {
        val paused = playing().copy(isPlaying = false, playWhenReady = false)
        assertFalse(endedWhilePlaying(paused, ended))
    }

    @Test
    fun `no state before the end does not move on`() {
        assertFalse(endedWhilePlaying(null, ended))
    }

    @Test
    fun `a state that is not the end does not move on`() {
        assertFalse(endedWhilePlaying(playing(), playing(position = 60_000L)))
    }

    @Test
    fun `another book does not move on`() {
        assertFalse(endedWhilePlaying(playing().copy(bookId = "b"), ended))
    }

    @Test
    fun `an end that was already seen does not move on again`() {
        assertFalse(endedWhilePlaying(ended, ended))
    }

    @Test
    fun `an end with an error does not move on`() {
        assertFalse(endedWhilePlaying(playing(), ended.copy(error = "Playback failed")))
    }

    @Test
    fun `a sleep timer due at the end stopped the book`() {
        // Half a second of book left and the timer runs out with it.
        assertFalse(endedWhilePlaying(playing(sleep = 500L), ended))
        assertFalse(endedWhilePlaying(playing(sleep = 3_000L), ended))
    }

    @Test
    fun `a sleep timer with time beyond the end moves on`() {
        assertTrue(endedWhilePlaying(playing(sleep = 20 * 60_000L), ended))
    }

    @Test
    fun `the sleep margin follows the speed`() {
        // 30 s of book at 2x is 15 s of listening: a 16 s timer is due at the end.
        assertFalse(endedWhilePlaying(playing(position = 30_000L, sleep = 16_000L, speed = 2f), ended))
        assertTrue(endedWhilePlaying(playing(position = 30_000L, sleep = 20_000L, speed = 2f), ended))
    }
}
