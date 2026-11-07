package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.domain.playback.UpNextOutcome
import com.chapterplayer.domain.playback.editUpNext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Selection mode of the book lists (Library and the author / narrator / series / collection pages): several entries
 * are picked, then one action runs over all of them. A selected entry is kept by its primary copy's id, but any copy's
 * id keeps it selected ([pruneSelection]), so a merged book whose primary copy changes stays selected, and an entry
 * that leaves the list leaves the selection. Each bulk action runs the same per-book rules as the book's own menu:
 * the mark goes to the copy played last ([markTarget]), favourites and Up next cover every copy of an entry, a download
 * takes only the copy the "Download from" rule picks without asking ([bulkDownloadPlan]).
 */

/** What a selection mode action does. */
enum class BulkAction {
    MARK_FINISHED, MARK_NOT_FINISHED, ADD_FAVOURITES, REMOVE_FAVOURITES, ADD_UP_NEXT, DOWNLOAD, REMOVE_DOWNLOAD,
}

/** [selected] with [entryId] added, or taken out when it was in. */
fun toggleSelected(selected: Set<String>, entryId: String): Set<String> =
    if (entryId in selected) selected - entryId else selected + entryId

/** "Select all": every entry of [items] (the list as filtered and shown). */
fun selectAllOf(items: List<BrowseItem>): Set<String> = items.mapTo(LinkedHashSet()) { it.book.id }

/**
 * The selection against the list as it is now: each entry any of whose copies is in [selected], by its current primary
 * id; entries that are no longer in [items] are dropped. Returns [selected] itself when nothing changed.
 */
fun pruneSelection(selected: Set<String>, items: List<BrowseItem>): Set<String> {
    if (selected.isEmpty()) return selected
    val kept = LinkedHashSet<String>()
    for (item in items) {
        if (item.book.id in selected || item.allSources.any { it.book.id in selected }) kept += item.book.id
    }
    return if (kept == selected) selected else kept
}

/** The entries of [items] that are selected, in the order shown. */
fun selectedEntries(items: List<BrowseItem>, selected: Set<String>): List<BrowseItem> =
    if (selected.isEmpty()) emptyList() else items.filter { it.book.id in selected }

/** "Remove from favourites" only when every selected entry is a favourite; otherwise "Add to favourites". */
fun bulkFavouriteAction(entries: List<BrowseItem>, favouriteIds: Set<String>): BulkAction =
    if (entries.isNotEmpty() && entries.all { isFavourite(it, favouriteIds) }) BulkAction.REMOVE_FAVOURITES
    else BulkAction.ADD_FAVOURITES

/**
 * The copy a mark goes to (`BookRepository.markFinished` marks the other copies too): the copy whose progress the entry
 * shows, the one played last; else [fallbackId] (the page's own copy, or the entry's primary one).
 */
fun markTarget(entry: BrowseItem?, fallbackId: String): String =
    entry?.progress?.bookId?.takeIf { entry.hasCopy(it) } ?: fallbackId

/**
 * Whether [entry] is already in the state a bulk mark asks for, so it is left alone. "Not finished" counts as already
 * done for every book that is not finished: pushing it would reset a book in progress to the start.
 */
fun alreadyMarked(entry: BrowseItem, finished: Boolean): Boolean = (entry.progress?.isFinished == true) == finished

/**
 * A bulk download: [copyIds] to queue (one per entry), [alreadyOnPhone] entries with a copy on the phone or on its way
 * already, [needsChoice] entries the "Download from" rule cannot decide for (Ask, or the chosen copy's server does not
 * answer), which are left out rather than asked about one by one.
 */
data class BulkDownloadPlan(val copyIds: List<String>, val alreadyOnPhone: Int, val needsChoice: Int)

/** What a bulk download of [entries] fetches; [context] is the merging's (null: no merging, every book is its own). */
fun bulkDownloadPlan(entries: List<BrowseItem>, context: MergeContext?): BulkDownloadPlan {
    val ids = mutableListOf<String>()
    var already = 0
    var needsChoice = 0
    for (entry in entries) {
        if (entry.allSources.any { it.downloadState != DownloadState.NOT_DOWNLOADED }) {
            already++
            continue
        }
        if (!entry.isMerged || context == null) {
            ids += entry.book.id
            continue
        }
        val chosen = chosenDownloadSource(entry, context.downloadChoice, context.onlineServerIds)
        if (chosen == null) needsChoice++ else ids += chosen.book.id
    }
    return BulkDownloadPlan(ids, already, needsChoice)
}

/** A bulk "Remove download": every downloaded copy of the entries, and how many entries had none on the phone. */
data class BulkRemovePlan(val copyIds: List<String>, val notDownloaded: Int)

fun bulkRemovePlan(entries: List<BrowseItem>): BulkRemovePlan {
    val ids = mutableListOf<String>()
    var none = 0
    for (entry in entries) {
        val copies = entry.allSources.filter { it.downloadState == DownloadState.DOWNLOADED }.map { it.book.id }
        if (copies.isEmpty()) none++ else ids += copies
    }
    return BulkRemovePlan(ids.distinct(), none)
}

/** A bulk "Add to Up next": the new queue and how many entries went in, were queued already, or are playing now. */
data class BulkUpNextResult(val queue: List<String>, val added: Int, val alreadyQueued: Int, val playingNow: Int)

/** [entries] added to the end of [queue] in the order shown, each through the one-book rule (`editUpNext`). */
fun addAllToUpNext(queue: List<String>, entries: List<BrowseItem>, loadedBookId: String?): BulkUpNextResult {
    var current = queue
    var added = 0
    var already = 0
    var playing = 0
    for (entry in entries) {
        val edit = editUpNext(current, favouriteRowIds(entry), loadedBookId, front = false)
        current = edit.queue
        when (edit.outcome) {
            UpNextOutcome.ADDED, UpNextOutcome.PLAYS_NEXT, UpNextOutcome.MOVED_TO_FRONT -> added++
            UpNextOutcome.ALREADY_QUEUED -> already++
            UpNextOutcome.PLAYING_NOW -> playing++
        }
    }
    return BulkUpNextResult(current, added, already, playing)
}

/**
 * What a bulk action did, for its one snackbar: [done] books (downloads for the download actions), [already] in that
 * state, [skipped] left out on purpose (a download that needs a choice, the book playing now), [queued] marks that
 * wait for their server, [partial] marks where another copy could not be changed, [failed] that could not be done.
 * [stoppedAfter] is set when Cancel stopped the run after that many of [total].
 */
data class BulkTally(
    val done: Int = 0,
    val already: Int = 0,
    val skipped: Int = 0,
    val queued: Int = 0,
    val partial: Int = 0,
    val failed: Int = 0,
    val stoppedAfter: Int? = null,
    val total: Int = 0,
)

/** The selection mode's title. */
fun selectionTitle(count: Int): String = "$count selected"

/** The top bar while an action runs: "Marking 37 of 1000…"; "Stopping…" after Cancel. */
fun bulkProgressText(action: BulkAction, done: Int, total: Int, stopping: Boolean = false): String {
    if (stopping) return "Stopping…"
    val verb = when (action) {
        BulkAction.MARK_FINISHED, BulkAction.MARK_NOT_FINISHED -> "Marking"
        BulkAction.ADD_FAVOURITES, BulkAction.REMOVE_FAVOURITES -> "Updating"
        BulkAction.ADD_UP_NEXT -> "Adding"
        BulkAction.DOWNLOAD -> "Starting"
        BulkAction.REMOVE_DOWNLOAD -> "Removing"
    }
    return "$verb $done of $total…"
}

private fun books(n: Int) = when (n) {
    0 -> "no books"
    1 -> "1 book"
    else -> "$n books"
}

private fun downloads(n: Int) = when (n) {
    0 -> "no downloads"
    1 -> "1 download"
    else -> "$n downloads"
}

/** The one snackbar line after a bulk action. */
fun bulkResultText(action: BulkAction, tally: BulkTally): String {
    val parts = mutableListOf<String>()
    tally.stoppedAfter?.let { parts += "Stopped after $it of ${tally.total}" }
    val n = tally.done
    parts += when (action) {
        BulkAction.MARK_FINISHED -> "Marked ${books(n)} as finished"
        BulkAction.MARK_NOT_FINISHED -> "Marked ${books(n)} as not finished"
        BulkAction.ADD_FAVOURITES -> "Added ${books(n)} to favourites"
        BulkAction.REMOVE_FAVOURITES -> "Removed ${books(n)} from favourites"
        BulkAction.ADD_UP_NEXT -> "Added ${books(n)} to Up next"
        BulkAction.DOWNLOAD -> "Downloading ${books(n)}"
        BulkAction.REMOVE_DOWNLOAD -> "Removed ${downloads(n)}"
    }
    if (tally.already > 0) parts += when (action) {
        BulkAction.MARK_FINISHED, BulkAction.MARK_NOT_FINISHED, BulkAction.ADD_FAVOURITES, BulkAction.REMOVE_FAVOURITES ->
            "${tally.already} already were"
        BulkAction.ADD_UP_NEXT -> "${tally.already} already in Up next"
        BulkAction.DOWNLOAD -> "${tally.already} already downloaded or on its way"
        BulkAction.REMOVE_DOWNLOAD -> "${tally.already} had no download"
    }
    if (tally.skipped > 0) parts += when (action) {
        BulkAction.ADD_UP_NEXT -> "Left out the book playing now"
        BulkAction.DOWNLOAD -> "Skipped ${tally.skipped} that need you to choose a server (open each book to choose)"
        else -> "Skipped ${tally.skipped}"
    }
    if (tally.queued > 0) parts += "${tally.queued} could not reach their server and will sync when it is back"
    if (tally.partial > 0) parts += "${tally.partial} had a copy that could not be changed"
    if (tally.failed > 0) parts += when (action) {
        BulkAction.DOWNLOAD -> "${tally.failed} could not start"
        BulkAction.REMOVE_DOWNLOAD -> "${tally.failed} could not be removed"
        else -> "${tally.failed} could not be changed"
    }
    return parts.joinToString(". ")
}

/** How many books a bulk mark sends to the servers at once (each mark may wait several seconds for them). */
const val BULK_MARK_PARALLELISM = 3

/**
 * Runs [block] over [items], at most [parallelism] at a time, in order of start. Once [isStopped] says so, no further
 * item starts; the running ones finish and what was done stays done. [onResult] hears each result as it comes (one at a
 * time). Returns the results by index, null for the items that never started. [block] must catch its own failures.
 */
suspend fun <T, R : Any> runBounded(
    items: List<T>,
    parallelism: Int,
    isStopped: () -> Boolean,
    onResult: suspend (index: Int, result: R) -> Unit = { _, _ -> },
    block: suspend (T) -> R,
): List<R?> {
    val results = MutableList<R?>(items.size) { null }
    if (items.isEmpty()) return results
    val next = Channel<Int>(Channel.UNLIMITED)
    for (i in items.indices) next.trySend(i)
    next.close()
    val lock = Mutex()
    coroutineScope {
        repeat(parallelism.coerceIn(1, items.size)) {
            launch {
                for (i in next) {
                    if (isStopped()) break
                    val result = block(items[i])
                    lock.withLock {
                        results[i] = result
                        onResult(i, result)
                    }
                }
            }
        }
    }
    return results
}
