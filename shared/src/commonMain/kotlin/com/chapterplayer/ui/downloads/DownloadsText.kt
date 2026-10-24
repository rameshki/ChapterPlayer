package com.chapterplayer.ui.downloads

import com.chapterplayer.data.downloads.DownloadRow
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.formatBytes
import com.chapterplayer.domain.downloads.formatTimeLeft

internal const val MISSING_BOOK_TITLE = "Book no longer in the library"

/** "42% · 3h 10m left" (or "42% · Calculating"), "Queued", "Paused · 42%", "Failed", or the size once done. */
internal fun downloadStatusLine(row: DownloadRow): String {
    val percent = (row.fraction * 100).toInt().coerceIn(0, 100)
    return when (row.status) {
        DownloadStatus.DOWNLOADING -> {
            val eta = row.etaSeconds
            if (eta == null || eta <= 0L) "$percent% · Calculating" else "$percent% · ${formatTimeLeft(eta)} left"
        }
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.PAUSED -> "Paused · $percent%"
        DownloadStatus.FAILED -> "Failed"
        DownloadStatus.DONE -> formatBytes(row.totalBytes)
    }
}

/** Downloads not finished yet first, then by title (ignoring case). */
internal fun sortDownloadItems(items: List<DownloadItem>): List<DownloadItem> =
    items.sortedWith(
        compareBy<DownloadItem>({ it.status == DownloadStatus.DONE }, { it.title.lowercase() }, { it.bookId })
    )
