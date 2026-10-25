package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.downloadStatusOf
import com.chapterplayer.domain.playback.LocalCopy
import com.chapterplayer.domain.playback.StoredFile
import com.chapterplayer.domain.playback.localCopyOf
import com.chapterplayer.domain.playback.shouldMarkFailed
import kotlinx.coroutines.CancellationException

/**
 * One downloaded audio file of a book. [ino] is the server's file id; [durationMs] is 0 and [mimeType] is ""
 * when the download did not record them.
 */
data class LocalFile(val ino: String, val path: String, val durationMs: Long, val mimeType: String)

/** The downloaded files of a book, for playing it from the phone. */
interface LocalBookFiles {
    /**
     * The files of [bookId] in book order when its download is finished and every file is on disk at its full
     * size; null otherwise. A finished download with a file gone is marked failed (see [MISSING_FILES_TEXT]).
     */
    suspend fun complete(bookId: String): List<LocalFile>?

    /** Records file lengths (by [LocalFile.ino]) learnt from a server session, for the files that have none. */
    suspend fun saveDurations(bookId: String, durationsMs: Map<String, Long>)
}

/** Nothing is ever on the phone. */
object NoLocalBookFiles : LocalBookFiles {
    override suspend fun complete(bookId: String): List<LocalFile>? = null
    override suspend fun saveDurations(bookId: String, durationsMs: Map<String, Long>) {}
}

const val MISSING_FILES_TEXT = "Some downloaded files are missing"

/**
 * [LocalBookFiles] over the download tables. [fileLength] is the size of the file at a path, or null when there
 * is no such file; a failure reading the disk means "not playable now" and changes nothing.
 */
class RoomLocalBookFiles(
    private val dao: DownloadDao,
    private val fileLength: (String) -> Long?,
    private val nowMs: () -> Long,
) : LocalBookFiles {

    override suspend fun complete(bookId: String): List<LocalFile>? {
        val row = dao.get(bookId) ?: return null
        val status = downloadStatusOf(row.status)
        if (status != DownloadStatus.DONE) return null
        val files = dao.getFiles(bookId).sortedBy { it.sortOrder }
        val onDisk = try {
            files.map { fileLength(it.path) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val copy = localCopyOf(status, files.zip(onDisk) { f, len -> StoredFile(f.totalBytes, len) })
        if (shouldMarkFailed(copy)) {
            // Only a row that is still DONE: a removal or a new download meanwhile wins.
            if (dao.updateStatusIf(bookId, listOf(DownloadStatus.DONE.name), DownloadStatus.FAILED.name, MISSING_FILES_TEXT, nowMs()) > 0) {
                // Try again fetches the missing files and keeps the whole ones.
                files.zip(onDisk).filter { (f, len) -> len != f.totalBytes }.forEach { (f, _) -> dao.updateFileProgress(bookId, f.ino, 0L) }
            }
        }
        if (copy != LocalCopy.COMPLETE) return null
        return files.map { LocalFile(it.ino, it.path, it.durationMs, it.mimeType) }
    }

    override suspend fun saveDurations(bookId: String, durationsMs: Map<String, Long>) {
        for ((ino, ms) in durationsMs) if (ms > 0L) dao.fillFileDuration(bookId, ino, ms)
    }
}
