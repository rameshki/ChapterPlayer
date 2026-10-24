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
import com.chapterplayer.domain.downloads.statusAfterStop
import com.chapterplayer.domain.downloads.statusOnEnqueue
import com.chapterplayer.domain.downloads.statusOnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Runs one book's download job in the background; WorkManager on the phone, a fake in tests. */
interface DownloadScheduler {
    /** Schedules the job unless one is already waiting or running; [replace] replaces it instead. */
    suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean)

    /** Stops the job (it saves its progress as it stops). */
    suspend fun cancel(bookId: String)

    /** Returns once the job is no longer running, or after a short wait. */
    suspend fun awaitStopped(bookId: String)
}

/**
 * The download rules over Room and a [DownloadScheduler]: the status is written first, then the job is
 * scheduled or stopped, so a stopping job never overwrites a pause (see `statusAfterStop`).
 */
class DefaultDownloadController(
    private val dao: DownloadDao,
    private val bookDao: BookDao,
    private val settings: AppSettings,
    private val location: DownloadLocation,
    private val scheduler: DownloadScheduler,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DownloadController {

    override suspend fun enqueue(bookId: String) {
        val book = bookDao.getById(bookId) ?: return
        val existing = dao.get(bookId)
        val next = statusOnEnqueue(existing?.let { downloadStatusOf(it.status) }) ?: return
        if (existing == null) {
            dao.upsert(DownloadEntity(bookId, book.serverId, next.name, totalBytes = 0, error = null, updatedAt = nowMs()))
        } else {
            dao.updateStatus(bookId, next.name, null, nowMs())
        }
        scheduler.schedule(bookId, settings.downloadWifiOnly.first(), replace = false)
    }

    override suspend fun pause(bookId: String) {
        if (!canPause(dao.get(bookId)?.let { downloadStatusOf(it.status) })) return
        dao.updateStatus(bookId, DownloadStatus.PAUSED.name, null, nowMs())
        scheduler.cancel(bookId)
    }

    override suspend fun resume(bookId: String) {
        val next = statusOnResume(dao.get(bookId)?.let { downloadStatusOf(it.status) }) ?: return
        dao.updateStatus(bookId, next.name, null, nowMs())
        scheduler.schedule(bookId, settings.downloadWifiOnly.first(), replace = false)
    }

    override suspend fun cancel(bookId: String) = delete(bookId)

    override suspend fun remove(bookId: String) = delete(bookId)

    /** After a reboot or process death: schedule every download that was waiting or running. */
    suspend fun restoreQueue() {
        val wifiOnly = settings.downloadWifiOnly.first()
        dao.observeAll().first()
            .filter { row -> downloadStatusOf(row.status)?.let(::resumesOnStart) == true }
            .forEach { scheduler.schedule(it.bookId, wifiOnly, replace = false) }
    }

    /** The job stopped; [timedOut] when the system's foreground time limit stopped it, which schedules it again. */
    suspend fun afterStop(bookId: String, timedOut: Boolean) {
        val status = dao.get(bookId)?.let { downloadStatusOf(it.status) }
        statusAfterStop(status)?.let { dao.updateStatus(bookId, it.name, null, nowMs()) }
        if (reenqueueAfterStop(timedOut, status)) {
            dao.updateStatus(bookId, DownloadStatus.QUEUED.name, null, nowMs())
            scheduler.schedule(bookId, settings.downloadWifiOnly.first(), replace = true)
        }
    }

    private suspend fun delete(bookId: String) {
        val serverId = dao.get(bookId)?.serverId ?: bookDao.getById(bookId)?.serverId
        scheduler.cancel(bookId)
        scheduler.awaitStopped(bookId)
        if (serverId != null) {
            withContext(Dispatchers.IO) { bookDirectory(location.rootDir(), serverId, bookId).deleteRecursively() }
        }
        dao.deleteBook(bookId)
    }
}
