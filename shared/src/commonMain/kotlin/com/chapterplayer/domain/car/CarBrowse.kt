package com.chapterplayer.domain.car

import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.domain.browse.BookGroup
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BrowseSort
import com.chapterplayer.domain.browse.groupByAuthor
import com.chapterplayer.domain.browse.groupByCollection
import com.chapterplayer.domain.browse.groupBySeries
import com.chapterplayer.domain.browse.isDimmed
import com.chapterplayer.domain.browse.isPlayable
import com.chapterplayer.domain.browse.sortItems
import com.chapterplayer.domain.playback.isFinishedAt

/** One row of a car list: a book (playable) or a folder (browsable) or a plain note. */
data class CarItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
    val browsable: Boolean,
    val playable: Boolean,
    val dimmed: Boolean,
)

/** What the tree is built from: the books of the visible libraries (with progress) and the collections. */
data class CarData(val items: List<BrowseItem>, val collections: List<BookCollection>)

const val CAR_MAX_SHORT_LIST = 50
const val CAR_NO_DOWNLOADS_NOTE = "No downloaded books yet"

private fun folder(id: String, title: String, subtitle: String = "") =
    CarItem(id, title, subtitle, null, browsable = true, playable = false, dimmed = false)

/** The four tabs under the root. */
fun carRoot(): List<CarItem> = listOf(
    folder(carNodeId("continue"), "Continue"),
    folder(carNodeId("recent"), "Recently added"),
    folder(carNodeId("library"), "Library"),
    folder(carNodeId("downloaded"), "Downloaded"),
)

/** The row of one book (Audiobookshelf or Plex): playable when it can be reached; dimmed otherwise. */
fun carBookItem(item: BrowseItem, online: Boolean): CarItem {
    val book = item.book
    val playable = isPlayable(item.downloadState, online, downloadedOnly = false)
    val percent = item.progress?.let { p ->
        if (p.isFinished || isFinishedAt(p.positionMs, book.durationMs)) 100 else (p.fraction * 100).toInt()
    }
    val subtitle = carSubtitle(book.author, percent, offline = isDimmed(item.downloadState, online, downloadedOnly = false))
    return CarItem(
        id = book.id,
        title = book.title,
        subtitle = subtitle,
        coverUrl = book.coverUrl,
        browsable = false,
        playable = playable,
        dimmed = !playable,
    )
}

private fun offlineBanner() = folder(carNodeId("downloaded"), "You are offline", "Open Downloaded")

private fun books(items: List<BrowseItem>, online: Boolean): List<CarItem> {
    val rows = items.map { carBookItem(it, online) }
    return if (online) rows else listOf(offlineBanner()) + rows
}

private fun groups(prefix: String, groups: List<BookGroup>): List<CarItem> =
    groups.map { folder(carNodeId(prefix, it.key), it.title, if (it.items.size == 1) "1 book" else "${it.items.size} books") }

/** The children of a node; null when the node is unknown. */
fun carChildren(nodeId: String, data: CarData, online: Boolean): List<CarItem>? {
    val node = parseCarNodeId(nodeId) ?: return null
    val items = data.items
    return when (node.kind) {
        "root" -> carRoot()
        "continue" -> books(
            sortItems(
                items.filter { it.progress != null && !it.progress.isFinished && it.progress.positionMs > 0 && !isFinishedAt(it.progress.positionMs, it.book.durationMs) },
                BrowseSort.RECENTLY_PLAYED,
            ).take(CAR_MAX_SHORT_LIST),
            online,
        )
        "recent" -> books(sortItems(items, BrowseSort.RECENTLY_ADDED).take(CAR_MAX_SHORT_LIST), online)
        "library" -> listOf(
            folder(carNodeId("series"), "Series"),
            folder(carNodeId("authors"), "Authors"),
            folder(carNodeId("collections"), "Collections"),
            folder(carNodeId("all"), "All books"),
        )
        "series" -> if (node.arg == null) groups("series", groupBySeries(items)) else
            groupBySeries(items).firstOrNull { it.key == node.arg }?.let { books(it.items, online) }
        "authors" -> groups("author", groupByAuthor(items))
        "author" -> groupByAuthor(items).firstOrNull { it.key == node.arg }?.let { books(it.items, online) }
        "collections" -> groups("collection", groupByCollection(items, data.collections))
        "collection" -> groupByCollection(items, data.collections).firstOrNull { it.key == node.arg }?.let { books(it.items, online) }
        "all" -> {
            val sorted = sortItems(items, BrowseSort.TITLE)
            val ranges = letterRanges(sorted.map { it.book.title })
            if (node.arg == null) {
                ranges.map { r -> folder(carNodeId("all", r.label), r.label, "${sorted.count { titleInRange(it.book.title, r) }} books") }
            } else {
                ranges.firstOrNull { it.label == node.arg }?.let { r -> books(sorted.filter { titleInRange(it.book.title, r) }, online) }
            }
        }
        // The books on the phone play online or not, so this list never gets the offline banner.
        "downloaded" -> sortItems(items.filter { it.downloadState == DownloadState.DOWNLOADED }, BrowseSort.TITLE)
            .map { carBookItem(it, online) }
            .ifEmpty { listOf(CarItem(carNodeId("note"), CAR_NO_DOWNLOADS_NOTE, "", null, browsable = false, playable = false, dimmed = true)) }
        else -> null
    }
}
