package com.chapterplayer.testing

import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.downloads.DownloadRow
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.domain.downloads.DownloadVolume
import com.chapterplayer.domain.downloads.INTERNAL_VOLUME_ID
import com.chapterplayer.data.permissions.NotificationPermission
import com.chapterplayer.domain.downloads.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Download rows held in memory; [used] is the storage used. */
class FakeDownloadOverview(rows: List<DownloadRow> = emptyList()) : DownloadOverview {
    val rows = MutableStateFlow(rows)
    val used = MutableStateFlow(0L)
    override fun observe(): Flow<List<DownloadRow>> = rows
    override fun observeBook(bookId: String): Flow<DownloadRow?> = rows.map { list -> list.firstOrNull { it.bookId == bookId } }
    override fun storageUsedBytes(): Flow<Long> = used
}

/** Records every call as "enqueue a", "pause a" and so on. */
class FakeDownloadController : DownloadController {
    val calls = mutableListOf<String>()
    var failWith: Exception? = null
    override suspend fun enqueue(bookId: String) = record("enqueue $bookId")
    override suspend fun pause(bookId: String) = record("pause $bookId")
    override suspend fun resume(bookId: String) = record("resume $bookId")
    override suspend fun cancel(bookId: String) = record("cancel $bookId")
    override suspend fun remove(bookId: String) = record("remove $bookId")
    override suspend fun reapplyWifiSetting() = record("reapply")
    private fun record(call: String) {
        calls += call
        failWith?.let { throw it }
    }
}

/** Storage with internal storage only unless [volumes] says otherwise; new downloads go to the first volume. */
class FakeDownloadStorage(
    var free: Long = 30_000_000_000,
    var volumes: List<DownloadVolume> = listOf(DownloadVolume(INTERNAL_VOLUME_ID, "Internal storage", 30_000_000_000)),
) : DownloadStorage {
    override suspend fun freeBytes(): Long = free
    override suspend fun volumes(): List<DownloadVolume> = volumes
}

class FakeNotificationPermission(var granted: Boolean = true) : NotificationPermission {
    var asks = 0
    override suspend fun ensure(): Boolean {
        asks++
        return granted
    }
}

fun downloadRow(
    bookId: String,
    status: DownloadStatus,
    fraction: Float = 0f,
    doneBytes: Long = 0,
    totalBytes: Long = 0,
    bytesPerSecond: Double = 0.0,
    etaSeconds: Long? = null,
    error: String? = null,
) = DownloadRow(bookId, status, fraction, doneBytes, totalBytes, bytesPerSecond, etaSeconds, error)
