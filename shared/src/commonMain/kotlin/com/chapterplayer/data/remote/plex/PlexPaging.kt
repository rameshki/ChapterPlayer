package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto

/** How many entries one section request asks for. */
const val PLEX_PAGE_SIZE = 200

/** 500 pages of 200 is 100,000 albums: far beyond any real library, so reaching it means the server is looping. */
const val PLEX_MAX_PAGES = 500

/** Tracks are many more than albums: 2,000 pages of 200 is 400,000 tracks. */
const val PLEX_MAX_TRACK_PAGES = 2_000

/**
 * Walks a paged Plex listing (`X-Plex-Container-Start` / `X-Plex-Container-Size`) from the start. With a reported
 * `totalSize` it goes on until that many entries were walked, even when the server sends smaller pages than asked (a
 * server may cap a page); without one, a short page is the last. It also stops when a page adds nothing new (a server
 * that ignores paging sends the whole list every time). Entries are de-duplicated by rating key, because an album added or removed
 * while paging shifts later pages. Gives up with [IllegalStateException] after [maxPages].
 */
suspend fun fetchAllPlexItems(
    pageSize: Int = PLEX_PAGE_SIZE,
    maxPages: Int = PLEX_MAX_PAGES,
    /**
     * Fail ([IllegalStateException]) instead of returning what was read when the walk ends short of the reported total
     * (an empty page, or a page that adds nothing): for sums that must cover every entry.
     */
    requireComplete: Boolean = false,
    /** After each page: the entries walked so far and the reported total (null when the server gives none). */
    onPage: (walked: Int, total: Int?) -> Unit = { _, _ -> },
    fetchPage: suspend (start: Int, size: Int) -> PlexLibraryAllResponse
): List<PlexMetadataDto> {
    val items = LinkedHashMap<String, PlexMetadataDto>()
    var start = 0
    repeat(maxPages) {
        val page = fetchPage(start, pageSize).mediaContainer
        val received = page.metadata.orEmpty()
        val sizeBefore = items.size
        received.forEach { if (it.ratingKey !in items) items[it.ratingKey] = it }
        start += received.size
        val total = page.totalSize
        val done = if (total != null) start >= total else received.size < pageSize
        val addedNothing = items.size == sizeBefore
        onPage(if (total != null) minOf(items.size, total) else items.size, total)
        if (done) return items.values.toList()
        if (addedNothing) {
            if (requireComplete && (total == null || items.size < total)) {
                throw IllegalStateException("Plex listing ended after ${items.size} of ${total ?: "?"} entries")
            }
            return items.values.toList()
        }
    }
    throw IllegalStateException("Plex section has more than $maxPages pages of $pageSize entries")
}
