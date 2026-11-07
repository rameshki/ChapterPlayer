package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BulkActionsTest {

    private fun item(
        id: String,
        serverId: String = "abs1",
        finished: Boolean? = null,
        playedAt: Long = 5L,
        download: DownloadState = DownloadState.NOT_DOWNLOADED,
    ) = BrowseItem(
        AudioBook(
            id = id, title = "Book $id", author = "Someone", narrator = "", durationMs = 1_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null,
            source = if (serverId.startsWith("plex")) ServerSource.PLEX else ServerSource.ABS, serverId = serverId,
        ),
        finished?.let { ReadingProgress(id, if (it) 1_000L else 400L, 1_000L, playedAt, isFinished = it) },
        download,
    )

    private fun merged(primary: BrowseItem, other: BrowseItem, progress: ReadingProgress? = primary.progress ?: other.progress) =
        primary.copy(sources = listOf(primary, other), progress = progress)

    // Selection

    @Test
    fun `toggling adds and removes one entry`() {
        assertEquals(setOf("a"), toggleSelected(emptySet(), "a"))
        assertEquals(emptySet(), toggleSelected(setOf("a"), "a"))
        assertEquals(setOf("a", "b"), toggleSelected(setOf("a"), "b"))
    }

    @Test
    fun `select all takes every entry shown in order`() {
        val list = listOf(item("c"), item("a"), item("b"))
        assertEquals(listOf("c", "a", "b"), selectAllOf(list).toList())
    }

    @Test
    fun `entries that left the list leave the selection`() {
        val list = listOf(item("a"), item("b"))
        assertEquals(setOf("a"), pruneSelection(setOf("a", "gone"), list))
        assertEquals(emptySet(), pruneSelection(setOf("gone"), list))
    }

    @Test
    fun `a merged entry stays selected when its primary copy changes`() {
        // Selected as "a"; the list now shows the entry as the Plex copy first.
        val entry = merged(item("p", "plex1"), item("a"))
        assertEquals(setOf("p"), pruneSelection(setOf("a"), listOf(entry)))
    }

    @Test
    fun `the selected entries come in the order shown`() {
        val list = listOf(item("c"), item("a"), item("b"))
        assertEquals(listOf("c", "b"), selectedEntries(list, setOf("b", "c")).map { it.book.id })
    }

    // Favourites

    @Test
    fun `remove from favourites only when every selected entry is one`() {
        val a = item("a")
        val b = item("b")
        assertEquals(BulkAction.REMOVE_FAVOURITES, bulkFavouriteAction(listOf(a, b), setOf("a", "b")))
        assertEquals(BulkAction.ADD_FAVOURITES, bulkFavouriteAction(listOf(a, b), setOf("a")))
        assertEquals(BulkAction.ADD_FAVOURITES, bulkFavouriteAction(emptyList(), setOf("a")))
    }

    @Test
    fun `a merged entry counts as a favourite through any copy`() {
        val entry = merged(item("a"), item("p", "plex1"))
        assertEquals(BulkAction.REMOVE_FAVOURITES, bulkFavouriteAction(listOf(entry), setOf("p")))
    }

    // Marks

    @Test
    fun `the mark goes to the copy played last and else the primary`() {
        val played = merged(item("a"), item("p", "plex1", finished = false), progress = ReadingProgress("p", 1L, 1_000L, 9L))
        assertEquals("p", markTarget(played, "a"))
        assertEquals("a", markTarget(item("a"), "a"))
        assertEquals("x", markTarget(null, "x"))
        // A progress row of a book that is not one of the copies never names the target.
        val odd = item("a").copy(progress = ReadingProgress("zzz", 1L, 1L, 1L))
        assertEquals("a", markTarget(odd, "a"))
    }

    @Test
    fun `an entry already in the asked state is not marked again`() {
        assertTrue(alreadyMarked(item("a", finished = true), finished = true))
        assertFalse(alreadyMarked(item("a", finished = false), finished = true))
        assertFalse(alreadyMarked(item("a"), finished = true))
        // Not finished is never pushed onto a book that is not finished: that would reset its place.
        assertTrue(alreadyMarked(item("a", finished = false), finished = false))
        assertTrue(alreadyMarked(item("a"), finished = false))
        assertFalse(alreadyMarked(item("a", finished = true), finished = false))
    }

    // Downloads

    @Test
    fun `download takes single books and the copy the rule picks`() {
        val context = MergeContext(downloadChoice = SourceChoice.OfType(ServerSource.PLEX), onlineServerIds = setOf("abs1", "plex1"))
        val single = item("s")
        val pick = merged(item("a"), item("p", "plex1"))
        val plan = bulkDownloadPlan(listOf(single, pick), context, statuses = emptyMap())
        assertEquals(listOf("s", "p"), plan.copyIds)
        assertEquals(emptyList(), plan.resumeIds)
        assertEquals(0, plan.alreadyOnPhone)
        assertEquals(0, plan.needsChoice)
    }

    @Test
    fun `download skips books that need a choice and books already on the phone`() {
        val ask = MergeContext(downloadChoice = SourceChoice.Ask, onlineServerIds = setOf("abs1", "plex1"))
        val needs = merged(item("a"), item("p", "plex1"))
        val onPhone = item("d", download = DownloadState.DOWNLOADED)
        val onItsWay = merged(item("b", download = DownloadState.DOWNLOADING), item("q", "plex1"))
        val plan = bulkDownloadPlan(
            listOf(needs, onPhone, onItsWay), ask,
            statuses = mapOf("d" to DownloadStatus.DONE, "b" to DownloadStatus.QUEUED),
        )
        assertEquals(emptyList(), plan.copyIds)
        assertEquals(1, plan.needsChoice)
        assertEquals(2, plan.alreadyOnPhone)
    }

    @Test
    fun `download skips a chosen copy whose server does not answer`() {
        val context = MergeContext(downloadChoice = SourceChoice.OfType(ServerSource.PLEX), onlineServerIds = setOf("abs1"))
        val plan = bulkDownloadPlan(listOf(merged(item("a"), item("p", "plex1"))), context, statuses = emptyMap())
        assertEquals(emptyList(), plan.copyIds)
        assertEquals(1, plan.needsChoice)
    }

    @Test
    fun `download resumes a paused or failed copy as the book's own control does`() {
        val context = MergeContext(downloadChoice = SourceChoice.OfType(ServerSource.PLEX), onlineServerIds = setOf("abs1", "plex1"))
        // The list reads every unfinished download as DOWNLOADING; the rows say what it really is.
        val paused = item("a", download = DownloadState.DOWNLOADING)
        val failed = merged(item("b", download = DownloadState.DOWNLOADING), item("p", "plex1"))
        val plan = bulkDownloadPlan(
            listOf(paused, failed), context,
            statuses = mapOf("a" to DownloadStatus.PAUSED, "b" to DownloadStatus.FAILED),
        )
        assertEquals(emptyList(), plan.copyIds)
        assertEquals(listOf("a", "b"), plan.resumeIds)
        assertEquals(0, plan.alreadyOnPhone)
    }

    @Test
    fun `download without the merging settings never picks a copy of a merged book`() {
        val plan = bulkDownloadPlan(listOf(item("s"), merged(item("a"), item("p", "plex1"))), context = null, statuses = emptyMap())
        assertEquals(listOf("s"), plan.copyIds)
        assertEquals(1, plan.needsChoice)
    }

    @Test
    fun `remove download takes every copy with a download finished or not`() {
        val both = merged(item("a", download = DownloadState.DOWNLOADED), item("p", "plex1", download = DownloadState.DOWNLOADED))
        val paused = item("r", download = DownloadState.DOWNLOADING)
        val none = item("n")
        val plan = bulkRemovePlan(
            listOf(both, paused, none),
            statuses = mapOf("a" to DownloadStatus.DONE, "p" to DownloadStatus.DONE, "r" to DownloadStatus.PAUSED),
        )
        assertEquals(listOf("a", "p", "r"), plan.copyIds)
        assertEquals(1, plan.notDownloaded)
    }

    // Confirmations and announcements

    @Test
    fun `remove download always asks and not finished asks above five books`() {
        assertTrue(bulkNeedsConfirmation(BulkAction.REMOVE_DOWNLOAD, 1))
        assertFalse(bulkNeedsConfirmation(BulkAction.REMOVE_DOWNLOAD, 0))
        assertFalse(bulkNeedsConfirmation(BulkAction.MARK_NOT_FINISHED, 5))
        assertTrue(bulkNeedsConfirmation(BulkAction.MARK_NOT_FINISHED, 6))
        assertFalse(bulkNeedsConfirmation(BulkAction.MARK_FINISHED, 1000))
        assertEquals("Mark 12 books as not finished?", bulkConfirmTitle(BulkAction.MARK_NOT_FINISHED, 12))
        assertEquals("Remove 1 download?", bulkConfirmTitle(BulkAction.REMOVE_DOWNLOAD, 1))
    }

    @Test
    fun `progress is announced at the start and every tenth only`() {
        assertEquals("Marking 0 of 1000…", bulkMilestoneText(BulkAction.MARK_FINISHED, 0, 1000))
        assertEquals("Marking 0 of 1000…", bulkMilestoneText(BulkAction.MARK_FINISHED, 99, 1000))
        assertEquals("Marking 100 of 1000…", bulkMilestoneText(BulkAction.MARK_FINISHED, 150, 1000))
        assertEquals("Marking 900 of 1000…", bulkMilestoneText(BulkAction.MARK_FINISHED, 999, 1000))
        val distinct = (0..1000).map { bulkMilestoneText(BulkAction.MARK_FINISHED, it, 1000) }.distinct()
        assertEquals(11, distinct.size)
        assertEquals("Removing 1 of 3…", bulkMilestoneText(BulkAction.REMOVE_DOWNLOAD, 1, 3))
    }

    // Up next

    @Test
    fun `add to up next appends in the order shown and skips queued and playing books`() {
        val entries = listOf(item("c"), merged(item("a"), item("p", "plex1")), item("b"), item("x"))
        val result = addAllToUpNext(queue = listOf("q", "p"), entries = entries, loadedBookId = "x")
        assertEquals(listOf("q", "p", "c", "b"), result.queue)
        assertEquals(2, result.added)
        assertEquals(1, result.alreadyQueued)
        assertEquals(1, result.playingNow)
    }

    // Texts

    @Test
    fun `the title counts the selection`() {
        assertEquals("1 selected", selectionTitle(1))
        assertEquals("12 selected", selectionTitle(12))
    }

    @Test
    fun `the progress line counts the books`() {
        assertEquals("Marking 37 of 1000…", bulkProgressText(BulkAction.MARK_FINISHED, 37, 1000))
        assertEquals("Removing 2 of 5…", bulkProgressText(BulkAction.REMOVE_DOWNLOAD, 2, 5))
        assertEquals("Stopping…", bulkProgressText(BulkAction.DOWNLOAD, 2, 5, stopping = true))
    }

    @Test
    fun `the result says what was done in one line`() {
        assertEquals("Marked 12 books as finished", bulkResultText(BulkAction.MARK_FINISHED, BulkTally(done = 12)))
        assertEquals("Marked 1 book as not finished", bulkResultText(BulkAction.MARK_NOT_FINISHED, BulkTally(done = 1)))
        assertEquals(
            "Marked 10 books as finished. 2 could not be changed",
            bulkResultText(BulkAction.MARK_FINISHED, BulkTally(done = 10, failed = 2)),
        )
        assertEquals(
            "Marked 10 books as finished. 2 could not reach their server and will sync when it is back",
            bulkResultText(BulkAction.MARK_FINISHED, BulkTally(done = 10, queued = 2)),
        )
        assertEquals(
            "Marked 3 books as finished. 2 already were",
            bulkResultText(BulkAction.MARK_FINISHED, BulkTally(done = 3, already = 2)),
        )
        assertEquals(
            "Marked 3 books as finished. 1 had a copy that could not be changed",
            bulkResultText(BulkAction.MARK_FINISHED, BulkTally(done = 3, partial = 1)),
        )
    }

    @Test
    fun `the result of the other actions`() {
        assertEquals("Added 4 books to favourites", bulkResultText(BulkAction.ADD_FAVOURITES, BulkTally(done = 4)))
        assertEquals("Removed 4 books from favourites", bulkResultText(BulkAction.REMOVE_FAVOURITES, BulkTally(done = 4)))
        assertEquals(
            "Added 2 books to Up next. 1 already in Up next. Left out the book playing now",
            bulkResultText(BulkAction.ADD_UP_NEXT, BulkTally(done = 2, already = 1, skipped = 1)),
        )
        assertEquals(
            "Downloading 3 books. 1 already downloaded or on its way. Skipped 2 that need you to choose a server (open each book to choose)",
            bulkResultText(BulkAction.DOWNLOAD, BulkTally(done = 3, already = 1, skipped = 2)),
        )
        assertEquals("Removed 1 download. 2 had no download", bulkResultText(BulkAction.REMOVE_DOWNLOAD, BulkTally(done = 1, already = 2)))
        assertEquals("Downloading 3 books. 2 of them carry on where they stopped", bulkResultText(BulkAction.DOWNLOAD, BulkTally(done = 3, resumed = 2)))
        assertEquals("Downloading no books. 1 could not start", bulkResultText(BulkAction.DOWNLOAD, BulkTally(failed = 1)))
    }

    @Test
    fun `a cancelled run says where it stopped`() {
        assertEquals(
            "Stopped after 37 of 1000. Marked 37 books as finished",
            bulkResultText(BulkAction.MARK_FINISHED, BulkTally(done = 37, stoppedAfter = 37, total = 1000)),
        )
    }

    // The runner

    @Test
    fun `the runner keeps at most the given number of books going at once`() = runTest {
        var running = 0
        var most = 0
        val results = runBounded((1..10).toList(), parallelism = 3, isStopped = { false }) { n ->
            running++
            most = maxOf(most, running)
            delay(10)
            running--
            n * 2
        }
        assertEquals(3, most)
        assertEquals((1..10).map { it * 2 }, results)
    }

    @Test
    fun `the runner starts no further book once stopped and keeps what was done`() = runTest {
        var stop = false
        val started = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            val results = runBounded((1..5).toList(), parallelism = 1, isStopped = { stop }) { n ->
                started += n
                if (n == 2) gate.await()
                n
            }
            assertEquals(listOf(1, 2, null, null, null), results)
        }
        advanceUntilIdle()
        stop = true
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals(listOf(1, 2), started)
    }

    @Test
    fun `the runner reports each result as it comes`() = runTest {
        val seen = mutableListOf<Int>()
        runBounded(listOf(1, 2, 3), parallelism = 1, isStopped = { false }, onResult = { _, r -> seen += r }) { it }
        assertEquals(listOf(1, 2, 3), seen)
        assertNull(runBounded(emptyList<Int>(), parallelism = 3, isStopped = { false }) { it }.firstOrNull())
    }
}
