package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemDto
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse

internal const val ABS_PAGE_SIZE = 100
internal const val ABS_MAX_PAGES = 500

/**
 * Fetches every item in a library by walking the server's pages.
 *
 * Stops when the reported total (if the server sends one) has been collected or a page comes back short
 * (the last page). Items are de-duplicated by id, because a book added or removed
 * while paging shifts later pages. Gives up with [IllegalStateException] after
 * [maxPages] so a misbehaving server cannot loop forever.
 */
internal suspend fun fetchAllAbsItems(
    pageSize: Int = ABS_PAGE_SIZE,
    maxPages: Int = ABS_MAX_PAGES,
    fetchPage: suspend (page: Int, limit: Int) -> AbsLibraryItemsResponse
): List<AbsLibraryItemDto> {
    val items = LinkedHashMap<String, AbsLibraryItemDto>()
    for (page in 0 until maxPages) {
        val response = fetchPage(page, pageSize)
        response.results.forEach { items.putIfAbsent(it.id, it) }
        val total = response.total
        val isLastPage = response.results.size < pageSize
        if (isLastPage || (total != null && items.size >= total)) return items.values.toList()
    }
    throw IllegalStateException("ABS library has more than $maxPages pages of $pageSize items")
}
