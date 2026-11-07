package com.chapterplayer.domain.downloads

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinishedDownloadsTest {

    private fun item(id: String, serverId: String = "abs1", finished: Boolean? = null, mirrored: Boolean = false) = BrowseItem(
        AudioBook(
            id = id, title = "Book $id", author = "Someone", narrator = "", durationMs = 1_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null,
            source = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS, serverId = serverId,
        ),
        finished?.let { ReadingProgress(id, 1_000L, 1_000L, 5L, isFinished = it, mirrored = mirrored) },
        DownloadState.DOWNLOADED,
    )

    private fun merged(primary: BrowseItem, other: BrowseItem, progress: ReadingProgress?) =
        primary.copy(sources = listOf(primary, other), progress = progress)

    private val gb = 1_000_000_000L

    @Test
    fun `finished books with a finished download qualify`() {
        val items = listOf(item("a", finished = true), item("b", finished = false), item("c"))
        val done = mapOf("a" to 2 * gb, "b" to gb, "c" to gb)
        val pick = finishedDownloadsToRemove(items, done, loadedBookId = null, upNextQueue = emptyList())
        assertEquals(listOf("a"), pick.bookIds)
        assertEquals(2 * gb, pick.bytes)
    }

    @Test
    fun `a merged book counts by the entry's progress and every downloaded copy goes`() {
        // The Plex copy is not finished itself, but the entry's progress (mirrored included) is.
        val entry = merged(
            item("a", finished = false), item("p", "plex1", finished = false),
            progress = ReadingProgress("p", 1_000L, 1_000L, 9L, isFinished = true, mirrored = true),
        )
        val pick = finishedDownloadsToRemove(listOf(entry), mapOf("a" to gb, "p" to gb), null, emptyList())
        assertEquals(listOf("a", "p"), pick.bookIds)
        assertEquals(2 * gb, pick.bytes)
    }

    @Test
    fun `a download that is not finished never qualifies`() {
        val pick = finishedDownloadsToRemove(listOf(item("a", finished = true)), doneBytes = emptyMap(), null, emptyList())
        assertEquals(emptyList(), pick.bookIds)
        assertEquals(0, pick.count)
    }

    @Test
    fun `the book in the player stays`() {
        val entry = merged(item("a", finished = true), item("p", "plex1"), ReadingProgress("a", 1L, 1L, 1L, isFinished = true))
        val pick = finishedDownloadsToRemove(listOf(entry, item("b", finished = true)), mapOf("a" to gb, "p" to gb, "b" to gb), loadedBookId = "p", upNextQueue = emptyList())
        assertEquals(listOf("b"), pick.bookIds)
    }

    @Test
    fun `the first book of up next stays and later ones do not`() {
        val items = listOf(item("a", finished = true), item("b", finished = true), item("c", finished = true))
        val done = mapOf("a" to gb, "b" to gb, "c" to gb)
        // "gone" is a stale row: the first entry that exists is "b".
        val pick = finishedDownloadsToRemove(items, done, null, upNextQueue = listOf("gone", "b", "c"))
        assertEquals(listOf("a", "c"), pick.bookIds)
    }

    @Test
    fun `the question names the count and the space`() {
        assertEquals("Remove 7 downloads and free 3.4 GB?", removeFinishedQuestion(FinishedDownloads(List(7) { "b$it" }, 3_400_000_000L)))
        assertEquals("Remove 1 download and free 350 MB?", removeFinishedQuestion(FinishedDownloads(listOf("a"), 350_000_000L)))
    }

    @Test
    fun `the result says what was removed`() {
        assertEquals("Removed 7 downloads and freed 3.4 GB", removeFinishedResult(removed = 7, failed = 0, freedBytes = 3_400_000_000L))
        assertEquals("Removed 5 downloads and freed 1 GB. 2 could not be removed", removeFinishedResult(5, 2, gb))
        assertEquals("No download could be removed. Try again.", removeFinishedResult(0, 2, 0L))
    }

    @Test
    fun `the row shows only when something qualifies`() {
        assertNull(removeFinishedSummary(FinishedDownloads(emptyList(), 0L)))
        assertEquals("7 downloads of finished books · 3.4 GB", removeFinishedSummary(FinishedDownloads(List(7) { "b$it" }, 3_400_000_000L)))
        assertEquals("1 download of a finished book · 350 MB", removeFinishedSummary(FinishedDownloads(listOf("a"), 350_000_000L)))
    }
}
