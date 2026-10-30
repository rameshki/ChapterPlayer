package com.chapterplayer.data.downloads

import com.chapterplayer.domain.downloads.BookProgress
import com.chapterplayer.domain.downloads.DownloadVolume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What a download says on iOS until the iOS downloader exists (Stage 5 step 5). */
const val DOWNLOADS_NOT_ON_IOS_TEXT = "Downloads are not available on iOS yet"

/** A download asked for where there is no downloader. Its message is [DOWNLOADS_NOT_ON_IOS_TEXT]. */
class DownloadsNotAvailableException : UnsupportedOperationException(DOWNLOADS_NOT_ON_IOS_TEXT)

/**
 * The iOS stand-in for [DownloadController] (Stage 5 step 5): starting or resuming a download fails with
 * [DownloadsNotAvailableException]; pausing, cancelling, removing and the Wi-Fi setting do nothing (nothing is downloading).
 */
class NotYetOnIosDownloadController : DownloadController {
    override suspend fun enqueue(bookId: String): Unit = throw DownloadsNotAvailableException()
    override suspend fun resume(bookId: String): Unit = throw DownloadsNotAvailableException()
    override suspend fun pause(bookId: String) = Unit
    override suspend fun cancel(bookId: String) = Unit
    override suspend fun remove(bookId: String) = Unit
    override suspend fun reapplyWifiSetting() = Unit
}

/** No download storage: no free space and no place to choose (the downloads settings say why). */
object NoDownloadStorage : DownloadStorage {
    override suspend fun freeBytes(): Long = 0L
    override suspend fun volumes(): List<DownloadVolume> = emptyList()
}

/** Nothing downloads in this process. */
object NoLiveDownloadProgress : LiveDownloadProgress {
    override fun observeAll(): Flow<Map<String, BookProgress>> = flowOf(emptyMap())
}

/** No downloaded files: every book streams (or cannot play offline). */
object NoLocalBookFiles : LocalBookFiles {
    override suspend fun complete(bookId: String): List<LocalFile>? = null
    override suspend fun saveDurations(bookId: String, durationsMs: Map<String, Long>) = Unit
}
