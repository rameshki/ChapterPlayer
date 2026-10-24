package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.canPause
import com.chapterplayer.domain.downloads.downloadStatusOf
import com.chapterplayer.domain.downloads.reenqueueAfterStop
import com.chapterplayer.domain.downloads.resumesOnStart
import com.chapterplayer.domain.downloads.safeFileName
import com.chapterplayer.domain.downloads.statusOnEnqueue
import com.chapterplayer.domain.downloads.statusOnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Runs one book's download job in the background; WorkManager on the phone, a fake in tests. */
interface DownloadScheduler {
    /** Schedules the job unless one is already waiting or running; [replace] replaces it instead. */
    suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean)

    /** Stops the job (it saves its progress as it stops; [DownloadJobs.whileIdle] waits for that). */
    suspend fun cancel(bookId: String)
}

/**
 * The download rules over Room and a [DownloadScheduler]: the status is written first (only from the status
 * it was read in), then the job is scheduled or stopped. A stopping job changes only a DOWNLOADING status, so
 * it never overwrites a pause. Files and rows are deleted only while no job of the book runs ([DownloadJobs]).
 */
class DefaultDownloadController(
    private val dao: DownloadDao,
    private val bookDao: BookDao,
    private val settings: AppSettings,
    private val location: DownloadLocation,
    private val scheduler: DownloadScheduler,
    private val jobs: DownloadJobs,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DownloadController {

    override suspend fun enqueue(bookId: String) {
        val book = bookDao.getById(bookId) ?: return
        val existing = dao.get(bookId)
        val next = statusOnEnqueue(existing?.let { downloadStatusOf(it.status) }) ?: return
        if (existing == null) {
            dao.upsert(DownloadEntity(bookId, book.serverId, next.name, totalBytes = 0, error = null, updatedAt = nowMs()))
        } else if (!move(bookId, existing.status, next)) {
            return
        }
        scheduler.schedule(bookId, settings.downloadWifiOnly.first(), replace = false)
    }

    override suspend fun pause(bookId: String) {
        val existing = dao.get(bookId) ?: return
        if (!canPause(downloadStatusOf(existing.status))) return
        if (!move(bookId, existing.status, DownloadStatus.PAUSED)) return
        scheduler.cancel(bookId)
    }

    override suspend fun resume(bookId: String) {
        val existing = dao.get(bookId) ?: return
        val next = statusOnResume(downloadStatusOf(existing.status)) ?: return
        if (!move(bookId, existing.status, next)) return
        // A job that is still stopping keeps the book until it has saved; the new one waits for it.
        scheduler.schedule(bookId, settings.downloadWifiOnly.first(), replace = false)
    }

    override suspend fun cancel(bookId: String) = delete(bookId)

    override suspend fun remove(bookId: String) = delete(bookId)

    /**
     * Replacing the work gives it the new network rule. A running job is stopped by that: it saves its bytes and goes
     * back to QUEUED, and the new job continues at the same byte once the old one has let go of the book.
     */
    override suspend fun reapplyWifiSetting() {
        val wifiOnly = settings.downloadWifiOnly.first()
        dao.observeAll().first()
            .filter { row -> downloadStatusOf(row.status)?.let(::resumesOnStart) == true }
            .forEach { scheduler.schedule(it.bookId, wifiOnly, replace = true) }
    }

    /** After a reboot or process death: schedule every download that was waiting or running. */
    suspend fun restoreQueue() {
        val wifiOnly = settings.downloadWifiOnly.first()
        dao.observeAll().first()
            .filter { row -> downloadStatusOf(row.status)?.let(::resumesOnStart) == true }
            .forEach { scheduler.schedule(it.bookId, wifiOnly, replace = false) }
    }

    /** The job stopped; [timedOut] when the system's foreground time limit stopped it, which schedules it again. */
    suspend fun afterStop(bookId: String, timedOut: Boolean) {
        // The status itself was already put back (DOWNLOADING to QUEUED) by the stopping job while it still owned
        // the book; changing it here could hit a newer job that has started since.
        val status = dao.get(bookId)?.let { downloadStatusOf(it.status) } ?: return
        if (reenqueueAfterStop(timedOut, status)) {
            scheduler.schedule(bookId, settings.downloadWifiOnly.first(), replace = true)
        }
    }

    private suspend fun delete(bookId: String) {
        val serverId = dao.get(bookId)?.serverId ?: bookDao.getById(bookId)?.serverId
        scheduler.cancel(bookId)
        // Waits until a stopping job has saved and let go, and keeps a new one out until the files are gone.
        jobs.whileIdle(bookId) {
            // The files stay where they were downloaded, so their recorded paths say which folder to delete; the
            // current location's folder is cleared too, for anything written before the file rows were.
            val folders = bookFolders(bookId, dao.getFiles(bookId).map { it.path }) +
                listOfNotNull(serverId?.let { bookDirectory(location.rootDir(), it, bookId) })
            withContext(Dispatchers.IO) { folders.distinct().forEach { it.deleteRecursively() } }
            dao.deleteBook(bookId)
        }
    }

    /** The folders that hold [paths]; only a book's own folder (named after the book) is ever returned. */
    private fun bookFolders(bookId: String, paths: List<String>): List<File> =
        paths.mapNotNull { File(it).absoluteFile.parentFile }
            .filter { it.name == safeFileName(bookId) && it.parentFile?.parentFile?.name == DOWNLOADS_FOLDER }

    private suspend fun move(bookId: String, from: String, to: DownloadStatus): Boolean =
        dao.updateStatusIf(bookId, listOf(from), to.name, null, nowMs()) > 0
}
