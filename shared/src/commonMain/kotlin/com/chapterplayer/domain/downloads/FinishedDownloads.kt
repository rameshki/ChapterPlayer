package com.chapterplayer.domain.downloads

import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.playback.planUpNext

/*
 * "Remove finished downloads" (the Downloads tab and Settings, Downloads and offline): the downloads of books the
 * person has finished, to free their space in one go. A book counts by its list entry's progress, so a book several
 * servers have is finished when the copy played last is (a mirrored row included), and then every copy of it that is
 * on the phone goes. Two books always stay: the one in the player and the book Up next will start when it ends, found
 * with the advancer's own rule (`planUpNext`: the first queued entry other than the loaded book that can play now).
 */

/** The downloads that would go ([bookIds], one per downloaded copy) and the space they hold. */
data class FinishedDownloads(val bookIds: List<String>, val bytes: Long) {
    val count: Int get() = bookIds.size
}

/**
 * The finished downloads to remove. [items] is the merged list; [doneBytes] the bytes of each copy whose download is
 * complete (by book id); [loadedBookId] the copy in the player; [upNextQueue] the stored Up next rows, in order;
 * [canPlay] whether a copy can play now (the advancer's `upNextCanPlay` with the current network and settings).
 */
fun finishedDownloadsToRemove(
    items: List<BrowseItem>,
    doneBytes: Map<String, Long>,
    loadedBookId: String?,
    upNextQueue: List<String>,
    canPlay: (BrowseItem) -> Boolean = { true },
): FinishedDownloads {
    if (doneBytes.isEmpty()) return FinishedDownloads(emptyList(), 0L)
    val keep = HashSet<String>()
    if (upNextQueue.isNotEmpty()) {
        // The loaded book counts as the one ending, so its own row does not stand for "next".
        val next = planUpNext(upNextQueue, items, loadedBookId, canPlay).start
        val nextEntry = next?.let { pick -> items.firstOrNull { e -> e.allSources.any { it.book.id == pick.copy.book.id } } }
        nextEntry?.allSources?.forEach { keep += it.book.id }
    }
    val ids = LinkedHashSet<String>()
    var bytes = 0L
    for (entry in items) {
        if (entry.progress?.isFinished != true) continue
        val copies = entry.allSources
        if (loadedBookId != null && copies.any { it.book.id == loadedBookId }) continue
        if (copies.any { it.book.id in keep }) continue
        for (copy in copies) {
            val size = doneBytes[copy.book.id] ?: continue
            if (!ids.add(copy.book.id)) continue
            bytes += size.coerceAtLeast(0L)
        }
    }
    return FinishedDownloads(ids.toList(), bytes)
}

private fun downloadsCount(n: Int) = if (n == 1) "1 download" else "$n downloads"

/** The confirmation: "Remove 7 downloads and free 3.4 GB?". */
fun removeFinishedQuestion(pick: FinishedDownloads): String =
    "Remove ${downloadsCount(pick.count)} and free ${formatBytes(pick.bytes)}?"

/** The second line of the row; null hides the row (nothing qualifies). */
fun removeFinishedSummary(pick: FinishedDownloads): String? = when (pick.count) {
    0 -> null
    1 -> "1 download of a finished book · ${formatBytes(pick.bytes)}"
    else -> "${pick.count} downloads of finished books · ${formatBytes(pick.bytes)}"
}

/** The one snackbar after the removal. */
fun removeFinishedResult(removed: Int, failed: Int, freedBytes: Long): String {
    if (removed == 0 && failed > 0) return "No download could be removed. Try again."
    val done = "Removed ${downloadsCount(removed)} and freed ${formatBytes(freedBytes)}"
    return if (failed > 0) "$done. $failed could not be removed" else done
}

const val REMOVE_FINISHED_TITLE = "Remove finished downloads"
