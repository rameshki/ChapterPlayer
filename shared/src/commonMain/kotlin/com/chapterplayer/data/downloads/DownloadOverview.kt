package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.domain.downloads.BookProgress
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.FileProgress
import com.chapterplayer.domain.downloads.downloadStatusOf
import com.chapterplayer.domain.downloads.overallFraction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One book's download as the screens show it. [fraction] and the bytes come from the stored files;
 * [bytesPerSecond] and [etaSeconds] are live while the book downloads in this process (0 and null otherwise).
 * [error] is set only for a failed download and is fixed, safe text.
 */
data class DownloadRow(
    val bookId: String,
    val status: DownloadStatus,
    val fraction: Float,
    val doneBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Double,
    val etaSeconds: Long?,
    val error: String?,
)

/** What the UI reads about downloads. */
interface DownloadOverview {
    /** Every download, in table order. */
    fun observe(): Flow<List<DownloadRow>>

    /** One book's download; null when the book has none. */
    fun observeBook(bookId: String): Flow<DownloadRow?>

    /** Bytes the downloads hold on the device now (finished and partly fetched files). */
    fun storageUsedBytes(): Flow<Long>
}

/** The live progress of the books downloading in this process, by book id. */
interface LiveDownloadProgress {
    fun observeAll(): Flow<Map<String, BookProgress>>
}

/** The rows the UI shows, from the two download tables and the live progress. */
fun downloadRows(
    downloads: List<DownloadEntity>,
    files: List<DownloadFileEntity>,
    live: Map<String, BookProgress>,
): List<DownloadRow> {
    val filesByBook = files.groupBy { it.bookId }
    return downloads.map { download ->
        val status = downloadStatusOf(download.status) ?: DownloadStatus.FAILED
        val bookFiles = filesByBook[download.bookId].orEmpty()
            .map { FileProgress(it.totalBytes, it.doneBytes.coerceIn(0L, it.totalBytes.coerceAtLeast(0L))) }
        val total = if (bookFiles.isEmpty()) download.totalBytes else bookFiles.sumOf { it.totalBytes }
        val done = bookFiles.sumOf { it.doneBytes }
        val fraction = if (status == DownloadStatus.DONE) 1f else overallFraction(bookFiles)
        val running = live[download.bookId]?.takeIf { status == DownloadStatus.DOWNLOADING }
        DownloadRow(
            bookId = download.bookId,
            status = status,
            fraction = fraction,
            doneBytes = done,
            totalBytes = total,
            bytesPerSecond = running?.bytesPerSecond ?: 0.0,
            etaSeconds = running?.etaSeconds,
            error = download.error.takeIf { status == DownloadStatus.FAILED },
        )
    }
}

/** The overview over Room and the downloader's live progress. */
class RoomDownloadOverview(
    private val dao: DownloadDao,
    private val live: LiveDownloadProgress,
) : DownloadOverview {

    override fun observe(): Flow<List<DownloadRow>> =
        combine(dao.observeAll(), dao.observeFiles(), live.observeAll(), ::downloadRows).distinctUntilChanged()

    override fun observeBook(bookId: String): Flow<DownloadRow?> =
        observe().map { rows -> rows.firstOrNull { it.bookId == bookId } }.distinctUntilChanged()

    override fun storageUsedBytes(): Flow<Long> =
        dao.observeFiles().map { files -> files.sumOf { it.doneBytes.coerceIn(0L, it.totalBytes.coerceAtLeast(0L)) } }
            .distinctUntilChanged()
}
