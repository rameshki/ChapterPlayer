package com.chapterplayer.data.downloads

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.playback.SIGN_IN_AGAIN_TEXT
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.dto.toAudioFileEntries
import com.chapterplayer.data.remote.abs.dto.toBookDetails
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.source.toServerItemId
import com.chapterplayer.domain.downloads.BookProgress
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.domain.downloads.FileProgress
import com.chapterplayer.domain.downloads.SpeedTracker
import com.chapterplayer.domain.downloads.backoffMs
import com.chapterplayer.domain.downloads.bookProgress
import com.chapterplayer.domain.downloads.hasRoomFor
import com.chapterplayer.domain.downloads.nextFileIndex
import com.chapterplayer.domain.downloads.planFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface BookResult {
    data object Done : BookResult
    /** Stopped for good; the row is FAILED with [message] (fixed, safe text). */
    data class Failed(val message: String) : BookResult
    /** Gave up for now after the allowed attempts; the row is QUEUED for a later run. */
    data object Retry : BookResult
    /** The download was cancelled or removed: nothing to do. */
    data object Missing : BookResult
    /** The user paused it: nothing to do. */
    data object Paused : BookResult
}

/**
 * Downloads one book, file after file, resuming where Room and the `.part` files say it stopped.
 * On the first run it asks the server for the file list (and saves the chapters if the book row has
 * none, and the cover if it is not stored yet), checks the space, and writes the `download_files` rows.
 * Failures that may pass (network, server errors, stalls) are retried after [backoffMs]; the attempt
 * count starts again whenever bytes arrived. When the job is stopped, the bytes so far are saved and a
 * book that was DOWNLOADING goes back to QUEUED (a pause has already set PAUSED and stays so).
 */
class BookDownloader(
    private val dao: DownloadDao,
    private val bookDao: BookDao,
    private val servers: ServerRepository,
    private val api: AbsApiService,
    private val authorizer: AbsAuthorizer,
    private val files: FileDownloader,
    private val location: DownloadLocation,
    private val limitBytes: suspend () -> Long? = { null },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = Int.MAX_VALUE,
    private val persistIntervalMs: Long = 2_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val progressByBook = MutableStateFlow<Map<String, BookProgress>>(emptyMap())

    /** The live progress of a book while (and after) this process downloads it; null before. */
    fun progress(bookId: String): Flow<BookProgress?> = progressByBook.map { it[bookId] }.distinctUntilChanged()

    suspend fun run(bookId: String): BookResult {
        val row = dao.get(bookId) ?: return BookResult.Missing
        when (row.status) {
            DownloadStatus.DONE.name -> return BookResult.Done
            DownloadStatus.PAUSED.name -> return BookResult.Paused
        }
        val server = servers.getById(row.serverId) ?: return fail(bookId, SERVER_GONE)
        if (bookDao.getById(bookId) == null) return fail(bookId, BOOK_GONE)
        setStatus(bookId, DownloadStatus.DOWNLOADING, null)

        val job = Job(bookId, server, bookDirectory(location.rootDir(), server.id, bookId))
        try {
            var attempt = 0
            while (true) {
                when (val step = job.attempt(row)) {
                    Step.Done -> {
                        setStatus(bookId, DownloadStatus.DONE, null)
                        return BookResult.Done
                    }
                    is Step.Failed -> return fail(bookId, step.message)
                    is Step.Again -> {
                        attempt = if (step.progressed) 1 else attempt + 1
                        if (attempt >= maxAttempts) {
                            setStatus(bookId, DownloadStatus.QUEUED, null)
                            return BookResult.Retry
                        }
                        sleep(backoffMs(attempt))
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                job.saveOnStop()
                if (dao.get(bookId)?.status == DownloadStatus.DOWNLOADING.name) setStatus(bookId, DownloadStatus.QUEUED, null)
            }
            throw e
        }
    }

    private sealed interface Step {
        data object Done : Step
        data class Failed(val message: String) : Step
        data class Again(val progressed: Boolean) : Step
    }

    private inner class Job(val bookId: String, val server: ServerConfig, val dir: File) {
        private val speed = SpeedTracker()
        private var rows: List<DownloadFileEntity> = emptyList()
        /** The file being written now, and its bytes so far. */
        @Volatile private var current: DownloadFileEntity? = null
        @Volatile private var currentDone = 0L

        suspend fun attempt(download: DownloadEntity): Step {
            rows = dao.getFiles(bookId)
            if (rows.isEmpty()) {
                val planned = try {
                    plan(download)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SignInRequiredException) {
                    return Step.Failed(SIGN_IN_AGAIN_TEXT)
                } catch (_: IOException) {
                    return Step.Again(progressed = false)
                } catch (_: Exception) {
                    return Step.Again(progressed = false)
                }
                if (planned != null) return planned
            }
            saveCoverIfMissing()

            var progressed = false
            while (true) {
                val index = nextFileIndex(rows.map { FileProgress(it.totalBytes, it.doneBytes) }) ?: return Step.Done
                val file = rows[index]
                val result = download(file)
                when (result) {
                    is FileResult.Done -> {
                        updateRow(file.copy(doneBytes = file.totalBytes, path = result.file.path))
                        progressed = true
                    }
                    is FileResult.Retry -> {
                        val moved = result.doneBytes > file.doneBytes
                        updateRow(file.copy(doneBytes = result.doneBytes.coerceIn(0L, file.totalBytes)))
                        return Step.Again(progressed || moved)
                    }
                    is FileResult.Failed -> return Step.Failed(result.message)
                }
            }
        }

        /** Asks the server for the files and writes the rows; a [Step] when the book cannot be downloaded, else null. */
        private suspend fun plan(download: DownloadEntity): Step? {
            val item = authorizer.authorized(server) { bearer -> api.getItem(server.baseUrl, bearer, bookId.toServerItemId()) }
            val planned = planFiles(item.toAudioFileEntries())
            if (planned.isEmpty()) return Step.Failed(NO_FILES)
            val needed = planned.sumOf { it.totalBytes }
            val used = dao.observeFiles().first().filter { it.bookId != bookId }.sumOf { it.doneBytes }
            if (!hasRoomFor(needed, location.freeBytes(), limitBytes(), used)) return Step.Failed(NO_SPACE)

            rows = planned.map { p ->
                DownloadFileEntity(
                    bookId = bookId, ino = p.ino, fileName = p.fileName,
                    path = targetsFor(dir, p.ino, p.fileName).final.path,
                    totalBytes = p.totalBytes, doneBytes = 0L, sortOrder = p.sortOrder
                )
            }
            dao.upsertFiles(rows)
            dao.upsert((dao.get(bookId) ?: download).copy(totalBytes = needed, updatedAt = nowMs()))

            // The book row already holds chapters after a sync; only an empty list is filled in.
            val book = bookDao.getById(bookId)
            val chapters = item.toBookDetails().chapters
            if (book != null && chapters.isNotEmpty() && ChapterJson.decode(book.chaptersJson).isEmpty()) {
                bookDao.updateChapters(bookId, ChapterJson.encode(chapters))
            }
            return null
        }

        private suspend fun saveCoverIfMissing() {
            val cover = File(dir, COVER_FILE)
            if (cover.isFile && cover.length() > 0L) return
            val url = bookDao.getById(bookId)?.coverUrl ?: return
            dir.mkdirs()
            files.downloadSmall(server, url, cover)
        }

        private suspend fun download(file: DownloadFileEntity): FileResult = coroutineScope {
            current = file
            currentDone = file.doneBytes
            report()
            val saver = launch {
                var saved = currentDone
                while (isActive) {
                    delay(persistIntervalMs)
                    val done = currentDone
                    if (done != saved) {
                        dao.updateFileProgress(bookId, file.ino, done)
                        saved = done
                    }
                }
            }
            try {
                files.download(
                    FileRequest(server, bookId.toServerItemId(), file.ino, file.fileName, dir, file.totalBytes, file.doneBytes)
                ) { done ->
                    currentDone = done
                    report()
                }
            } finally {
                saver.cancel()
            }
        }

        private suspend fun updateRow(row: DownloadFileEntity) {
            rows = rows.map { if (it.ino == row.ino) row else it }
            dao.upsertFiles(listOf(row))
            current = null
            report()
        }

        private fun report() {
            val live = current
            val progress = rows.map {
                FileProgress(it.totalBytes, if (live != null && it.ino == live.ino) currentDone else it.doneBytes)
            }
            speed.add(nowMs(), progress.sumOf { it.doneBytes })
            progressByBook.update { it + (bookId to bookProgress(progress, speed.bytesPerSecond())) }
        }

        /** Saves what is in the current `.part` file (everything written is on disk once the writer closed). */
        suspend fun saveOnStop() {
            val file = current ?: return
            val part = targetsFor(dir, file.ino, file.fileName).part
            val done = (if (part.isFile) part.length() else currentDone).coerceIn(0L, file.totalBytes)
            dao.updateFileProgress(bookId, file.ino, done)
        }
    }

    private suspend fun setStatus(bookId: String, status: DownloadStatus, error: String?) =
        dao.updateStatus(bookId, status.name, error, nowMs())

    private suspend fun fail(bookId: String, message: String): BookResult {
        setStatus(bookId, DownloadStatus.FAILED, message)
        return BookResult.Failed(message)
    }

    private companion object {
        const val COVER_FILE = "cover.jpg"
        const val NO_SPACE = "Not enough space"
        const val NO_FILES = "This book has no audio files to download"
        const val SERVER_GONE = "The book's server is no longer configured"
        const val BOOK_GONE = "This book is no longer in the library"
    }
}
