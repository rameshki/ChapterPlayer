package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemDto

/** How many items one `/Items` request asks for. */
const val JELLYFIN_PAGE_SIZE = 200

/** 500 pages of 200 is 100,000 books: far beyond any real library, so reaching it means the server is looping. */
const val JELLYFIN_MAX_PAGES = 500

/**
 * Every `AudioBook` of the view [viewId], page after page, until `TotalRecordCount` items were walked (without a
 * total, a short page is the last). An empty page ends the walk early. The next page starts after what was received,
 * so a server that caps its pages is still walked to the end. Items are de-duplicated by id (an item added while
 * paging shifts later pages). Gives up with [IllegalStateException] after [JELLYFIN_MAX_PAGES] pages.
 */
suspend fun fetchAllAudiobooks(
    api: JellyfinApiService,
    base: String,
    deviceId: String,
    token: String,
    viewId: String,
): List<JellyfinItemDto> {
    val items = LinkedHashMap<String, JellyfinItemDto>()
    var start = 0
    repeat(JELLYFIN_MAX_PAGES) {
        val page = api.getAudiobooksPage(base, deviceId, token, viewId, start)
        val received = page.items
        if (received.isEmpty()) return items.values.toList()
        received.forEach { item -> val id = item.id.orEmpty(); if (id.isNotEmpty() && id !in items) items[id] = item }
        start += received.size
        val total = page.totalRecordCount
        val done = if (total != null) start >= total else received.size < JELLYFIN_PAGE_SIZE
        if (done) return items.values.toList()
    }
    throw IllegalStateException("Jellyfin view has more than $JELLYFIN_MAX_PAGES pages of $JELLYFIN_PAGE_SIZE items")
}
