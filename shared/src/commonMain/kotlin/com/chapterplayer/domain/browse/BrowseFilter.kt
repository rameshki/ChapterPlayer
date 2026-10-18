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
        
        progressMatch && downloadMatch
    }
    
    return sortItems(filtered, query.sort)
}