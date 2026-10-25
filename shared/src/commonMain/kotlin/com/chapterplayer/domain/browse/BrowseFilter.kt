package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress

enum class ProgressFilter { ALL, IN_PROGRESS, NOT_STARTED, FINISHED }
enum class DownloadFilter { ALL, DOWNLOADED, NOT_DOWNLOADED }
data class BrowseItem(val book: AudioBook, val progress: ReadingProgress?, val downloadState: DownloadState)
data class BrowseQuery(
    val progress: ProgressFilter = ProgressFilter.ALL,
    val download: DownloadFilter = DownloadFilter.ALL,
    val sort: BrowseSort = BrowseSort.RECENTLY_ADDED,
    /** The servers whose books show; empty means every server. */
    val serverIds: Set<String> = emptySet(),
)
fun applyQuery(items: List<BrowseItem>, query: BrowseQuery): List<BrowseItem> {
    val filtered = items.filter { item ->
        val progressMatch = when (query.progress) {
            ProgressFilter.ALL -> true
            ProgressFilter.IN_PROGRESS -> item.progress != null && !item.progress.isFinished && item.progress.positionMs > 0L
            ProgressFilter.NOT_STARTED -> item.progress == null || (!item.progress.isFinished && item.progress.positionMs == 0L)
            ProgressFilter.FINISHED -> item.progress != null && item.progress.isFinished
        }
        
        val downloadMatch = when (query.download) {
            DownloadFilter.ALL -> true
            DownloadFilter.DOWNLOADED -> item.downloadState == DownloadState.DOWNLOADED
            DownloadFilter.NOT_DOWNLOADED -> item.downloadState != DownloadState.DOWNLOADED
        }
        
        val serverMatch = query.serverIds.isEmpty() || item.book.serverId in query.serverIds

        progressMatch && downloadMatch && serverMatch
    }
    
    return sortItems(filtered, query.sort)
}

/**
 * [query] with only the server ids that still exist. A choice made only of deleted servers becomes
 * "every server", so a stale id never hides the whole library; so does any choice once fewer than two
 * servers remain, because the Server section (the only way to change it) is hidden then.
 */
fun pruneServerFilter(query: BrowseQuery, existingServerIds: Set<String>): BrowseQuery {
    if (query.serverIds.isEmpty()) return query
    if (existingServerIds.size < 2) return query.copy(serverIds = emptySet())
    val kept = query.serverIds.filterTo(mutableSetOf()) { it in existingServerIds }
    return if (kept == query.serverIds) query else query.copy(serverIds = kept)
}
