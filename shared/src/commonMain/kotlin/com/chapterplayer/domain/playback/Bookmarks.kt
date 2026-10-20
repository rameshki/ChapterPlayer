package com.chapterplayer.domain.playback

import com.chapterplayer.domain.browse.formatClock

/**
 * Represents the synchronization state of a bookmark.
 */
enum class BookmarkState { SYNCED, PENDING_CREATE, PENDING_RENAME, PENDING_DELETE }

/**
 * Represents a bookmark at a specific time in a book.
 */
data class Bookmark(val bookId: String, val timeSec: Long, val title: String, val createdAt: Long, val state: BookmarkState)

/**
 * Container for the results of a bookmark synchronization process.
 */
data class BookmarkMerge(val result: List<Bookmark>, val toCreate: List<Bookmark>, val toRename: List<Bookmark>, val toDelete: List<Bookmark>)

/**
 * Generates a default title string containing the formatted clock time.
 */
fun defaultBookmarkTitle(positionMs: Long): String = "Bookmark at ${formatClock(positionMs)}"

/**
 * Returns the trimmed input title, or the default title if the input is null or blank.
 */
fun bookmarkTitleOrDefault(input: String?, positionMs: Long): String {
    val trimmed = input?.trim()
    return if (trimmed.isNullOrEmpty()) defaultBookmarkTitle(positionMs) else trimmed
}

/**
 * Returns a list of bookmarks that are not marked for deletion, sorted by time and creation date.
 */
fun visibleBookmarks(all: List<Bookmark>): List<Bookmark> {
    return all.filter { it.state != BookmarkState.PENDING_DELETE }
        .sortedWith(compareBy<Bookmark> { it.timeSec }.thenBy { it.createdAt })
}

/**
 * Merges local and server bookmark lists into a single consistent state based on time synchronization rules.
 */
fun mergeBookmarks(local: List<Bookmark>, server: List<Bookmark>): BookmarkMerge {
    val localMap = local.associateBy { it.timeSec }
    val serverMap = server.associateBy { it.timeSec }
    val allTimes = (localMap.keys + serverMap.keys).sorted()

    val resultList = mutableListOf<Bookmark>()
    val toCreateList = mutableListOf<Bookmark>()
    val toRenameList = mutableListOf<Bookmark>()
    val toDeleteList = mutableListOf<Bookmark>()

    for (time in allTimes) {
        val local = localMap[time]
        val server = serverMap[time]

        if (server != null && local == null) {
            resultList.add(server)
        } else if (server != null && local != null) {
            when (local.state) {
                BookmarkState.SYNCED -> resultList.add(server)
                BookmarkState.PENDING_CREATE -> resultList.add(server)
                BookmarkState.PENDING_RENAME -> {
                    resultList.add(local)
                    toRenameList.add(local)
                }
                BookmarkState.PENDING_DELETE -> {
                    resultList.add(local)
                    toDeleteList.add(local)
                }
            }
        } else if (local != null && server == null) {
            if (local.state == BookmarkState.PENDING_CREATE) {
                resultList.add(local)
                toCreateList.add(local)
            }
            // Other local states are dropped if no server record exists
        }
    }

    return BookmarkMerge(
        result = resultList.sortedBy { it.timeSec },
        toCreate = toCreateList.sortedBy { it.timeSec },
        toRename = toRenameList.sortedBy { it.timeSec },
        toDelete = toDeleteList.sortedBy { it.timeSec }
    )
}
