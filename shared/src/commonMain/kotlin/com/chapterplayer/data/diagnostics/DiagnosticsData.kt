package com.chapterplayer.data.diagnostics

import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.local.dao.PendingProgressDao
import com.chapterplayer.domain.diagnostics.DiagDownload
import com.chapterplayer.domain.playback.PendingProgress

/** The tables the Diagnostics page reads beyond the book list, read only. */
interface DiagnosticsData {
    /** The queued progress rows of [bookId] (at most one: the queue keeps one row per book). */
    suspend fun pending(bookId: String): List<PendingProgress>

    /** The book's download as stored, or null when it has none. File names and the folder name only, never a full path. */
    suspend fun download(bookId: String): DiagDownload?
}

/** Where the report is written for `adb logcat -s ChapterPlayerDiag` (Logcat on Android, the console on iOS). */
fun interface DiagnosticsLogger {
    fun log(report: String)
}

/** Writes through the platform's log. */
class PlatformDiagnosticsLogger : DiagnosticsLogger {
    override fun log(report: String) = writeDiagnosticsLog(report)
}

/** The tag the report is logged under. */
const val DIAGNOSTICS_LOG_TAG = "ChapterPlayerDiag"

internal expect fun writeDiagnosticsLog(report: String)

/** The Room tables behind [DiagnosticsData]. */
class RoomDiagnosticsData(
    private val pendingDao: PendingProgressDao,
    private val downloadDao: DownloadDao,
) : DiagnosticsData {

    override suspend fun pending(bookId: String): List<PendingProgress> =
        listOfNotNull(pendingDao.get(bookId)?.toDomain())

    override suspend fun download(bookId: String): DiagDownload? {
        val row = downloadDao.get(bookId) ?: return null
        val files = downloadDao.getFiles(bookId).sortedBy { it.sortOrder }
        return DiagDownload(
            status = row.status,
            doneBytes = files.sumOf { it.doneBytes },
            totalBytes = if (files.isEmpty()) row.totalBytes else files.sumOf { it.totalBytes },
            fileNames = files.map { it.fileName },
            folder = files.firstOrNull()?.let { folderNameOf(it.path) },
            error = row.error,
        )
    }
}

/** The name of the folder that holds the file at [path] (the book's folder), without anything above it. */
internal fun folderNameOf(path: String): String? {
    val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    return if (parts.size >= 2) parts[parts.size - 2] else null
}
