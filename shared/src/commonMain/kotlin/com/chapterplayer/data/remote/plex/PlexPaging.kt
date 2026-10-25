package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto

/** How many entries one section request asks for. */
const val PLEX_PAGE_SIZE = 200

/** 500 pages of 200 is 100,000 albums: far beyond any real library, so reaching it means the server is looping. */
const val PLEX_MAX_PAGES = 500

/**
 * Walks a paged Plex listing (`X-Plex-Container-Start` / `X-Plex-Container-Size`) from the start. Stops when a page
 * comes back short, when the reported `totalSize` has been read, or when a page adds nothing new (a server that ignores
 * paging sends the whole list every time). Entries are de-duplicated by rating key, because an album added or removed
 * while paging shifts later pages. Gives up with [IllegalStateException] after [maxPages].
 */
suspend fun fetchAllPlexItems(
    pageSize: Int = PLEX_PAGE_SIZE,
    maxPages: Int = PLEX_MAX_PAGES,
    fetchPage: suspend (start: Int, size: Int) -> PlexLibraryAllResponse
): List<PlexMetadataDto> {
    val items = LinkedHashMap<String, PlexMetadataDto>()
    var start = 0
    repeat(maxPages) {
        val page = fetchPage(start, pageSize).mediaContainer
        val received = page.metadata.orEmpty()
        val sizeBefore = items.size
        received.forEach { items.putIfAbsent(it.ratingKey, it) }
        start += received.size
        val total = page.totalSize
        val isLastPage = received.size < pageSize
        val addedNothing = items.size == sizeBefore
        if (isLastPage || addedNothing || (total != null && start >= total)) return items.values.toList()
    }
    throw IllegalStateException("Plex section has more than $maxPages pages of $pageSize entries")
}
