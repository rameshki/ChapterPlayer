package com.chapterplayer.data.downloads

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.DownloadDao
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
import com.chapterplayer.domain.downloads.PART_SUFFIX
import com.chapterplayer.domain.downloads.SpeedTracker
import com.chapterplayer.domain.downloads.backoffMs
import com.chapterplayer.domain.downloads.bookProgress
import com.chapterplayer.domain.downloads.RoomCheck
import com.chapterplayer.domain.downloads.roomCheck
import com.chapterplayer.domain.downloads.nextFileIndex
import com.chapterplayer.domain.downloads.planFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface BookResult {
    data object Done : BookResult
    /** Stopped for good; the row is FAILED with [message] (fixed, safe text). */
    data class Failed(val message: String) : BookResult
    /** Gave up for now after the allowed attempts; the row is QUEUED and the scheduler's backoff takes over. */
    data object Retry : BookResult
    /** The download was cancelled or removed: nothing to do. */
    data object Missing : BookResult
    /** The user paused it: nothing to do. */
    data object Paused : BookResult
}

/** Who may touch a book's download files and rows right now. */
interface DownloadJobs {
    /** Runs [block] while no download job of [bookId] runs, and keeps new ones waiting until it ends. */
    suspend fun <T> whileIdle(bookId: String, block: suspend () -> T): T
}

/**
 * Downloads one book, file after file, resuming where Room and the `.part` files say it stopped.
 * On the first run it asks the server for the file list (and saves the chapters if the book row has
 * none, and the cover if it is not stored yet), checks the space, and writes the `download_files` rows.
 *
 * Ownership: one job per book at a time (a later job, or [whileIdle], waits until the earlier one has
 * fully stopped and saved), and one book downloading at a time across the app, so the space check holds.
 * Statuses change only from the expected ones, so a pause or a removal is never overwritten.
 *
 * Failures that may pass (network, server errors, stalls) are retried after [backoffMs], at most
 * [maxAttempts] times in a row without progress; then the job ends with [BookResult.Retry] and the
 * scheduler's own backoff takes over. When the job is stopped, the bytes so far are saved and a book that
 * was DOWNLOADING goes back to QUEUED.
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
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val persistIntervalMs: Long = 2_000,
    private val persistEveryBytes: Long = 1L shl 20,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DownloadJobs, LiveDownloadProgress {
    private val progressByBook = MutableStateFlow<Map<String, BookProgress>>(emptyMap())
    private val bookLocks = ConcurrentHashMap<String, Mutex>()
    private val downloadSlot = Semaphore(1)

    /** The live progress of a book while (and after) this process downloads it; null before. */
    fun progress(bookId: String): Flow<BookProgress?> = progressByBook.map { it[bookId] }.distinctUntilChanged()

    override fun observeAll(): Flow<Map<String, BookProgress>> = progressByBook

    override suspend fun <T> whileIdle(bookId: String, block: suspend () -> T): T = lockOf(bookId).withLock { block() }

    private fun lockOf(bookId: String) = bookLocks.computeIfAbsent(bookId) { Mutex() }

    /** [onStart] runs once this book has the download slot, right before any transfer (the worker goes foreground then). */
    suspend fun run(bookId: String, onStart: suspend () -> Unit = {}): BookResult =
        lockOf(bookId).withLock {
            progressByBook.update { it - bookId }
            var result: BookResult? = null
            try {
                downloadSlot.withPermit { runOwned(bookId, onStart).also { result = it } }
            } finally {
                // A book that stopped or is waiting must not replay an old progress value; a finished one keeps its last.
                if (result != BookResult.Done) progressByBook.update { it - bookId }
            }
        }

    private suspend fun runOwned(bookId: String, onStart: suspend () -> Unit): BookResult {
        val row = dao.get(bookId) ?: return BookResult.Missing
        if (!moveStatus(bookId, listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING), DownloadStatus.DOWNLOADING)) {
            return when (dao.get(bookId)?.status) {
                null -> BookResult.Missing
                DownloadStatus.DONE.name -> BookResult.Done
                DownloadStatus.PAUSED.name -> BookResult.Paused
                else -> BookResult.Failed(dao.get(bookId)?.error ?: FAILED)
            }
        }
        var job: Job? = null
        try {
            val server = servers.getById(row.serverId) ?: return fail(bookId, SERVER_GONE)
            if (bookDao.getById(bookId) == null) return fail(bookId, BOOK_GONE)
            onStart()
            val current = Job(bookId, server, bookDirectory(location.rootDir(), server.id, bookId))
            job = current
            var attempt = 0
            while (true) {
                when (val step = current.attempt()) {
                    Step.Done -> {
                        moveStatus(bookId, listOf(DownloadStatus.DOWNLOADING), DownloadStatus.DONE)
                        return BookResult.Done
                    }
                    is Step.Failed -> return fail(bookId, step.message)
                    Step.Gone -> return BookResult.Missing
                    is Step.Again -> {
                        attempt = if (step.progressed) 1 else attempt + 1
                        if (attempt >= maxAttempts) {
                            moveStatus(bookId, listOf(DownloadStatus.DOWNLOADING), DownloadStatus.QUEUED)
                            return BookResult.Retry
                        }
                        sleep(backoffMs(attempt))
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                job?.saveOnStop()
                moveStatus(bookId, listOf(DownloadStatus.DOWNLOADING), DownloadStatus.QUEUED)
            }
            throw e
        } catch (_: Exception) {
            // A bug or an unexpected platform error: stop with a fixed message rather than crash the worker.
            withContext(NonCancellable) { job?.saveOnStop() }
            return fail(bookId, FAILED)
        }
    }

    private sealed interface Step {
        data object Done : Step
        data class Failed(val message: String) : Step
        data class Again(val progressed: Boolean) : Step
        /** The download row was removed while planning. */
        data object Gone : Step
    }

    /**
     * [newDir] is where a book not planned yet goes (the location chosen now). A planned book stays in the folder
     * its file rows name, so changing the location in Settings never moves or splits a download.
     */
    private inner class Job(val bookId: String, val server: ServerConfig, private val newDir: File) {
        var dir: File = newDir
            private set
        private val speed = SpeedTracker()
        private var rows: List<DownloadFileEntity> = emptyList()
        /** The file being written now, and its bytes so far. */
        @Volatile private var current: DownloadFileEntity? = null
        @Volatile private var currentDone = 0L
        private var limitChecked = false

        private suspend fun otherFiles() = dao.observeFiles().first().filter { it.bookId != bookId }

        private suspend fun othersPlannedBytes() = otherFiles().sumOf { it.totalBytes }

        suspend fun attempt(): Step {
            rows = dao.getFiles(bookId)
            dir = rows.firstOrNull()?.let { File(it.path).parentFile } ?: dir
            if (rows.isEmpty()) {
                val planned = try {
                    plan()
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
            } else if (!limitChecked) {
                // A planned book is checked against the limit again once per job: it may have been lowered since.
                limitChecked = true
                val total = rows.sumOf { it.totalBytes }
                if (roomCheck(total, 0L, 0L, othersPlannedBytes(), Long.MAX_VALUE, limitBytes(), safetyBytes = 0L) == RoomCheck.LIMIT_REACHED) {
                    return Step.Failed(LIMIT_REACHED_TEXT)
                }
            }
            saveCoverIfMissing()

            var progressed = false
            while (true) {
                val index = nextFileIndex(rows.map { FileProgress(it.totalBytes, it.doneBytes) }) ?: return Step.Done
                val file = rows[index]
                when (val result = download(file)) {
                    is FileResult.Done -> {
                        saveDone(file, file.totalBytes)
                        progressed = true
                    }
                    is FileResult.Retry -> {
                        val moved = result.doneBytes > file.doneBytes
                        saveDone(file, result.doneBytes.coerceIn(0L, file.totalBytes))
                        return Step.Again(progressed || moved)
                    }
                    is FileResult.Failed -> {
                        if (result.replan) dropPlan()
                        return Step.Failed(result.message)
                    }
                }
            }
        }

        /** Asks the server for the files and writes the rows; a [Step] when the book cannot be downloaded, else null. */
        private suspend fun plan(): Step? {
            val item = authorizer.authorized(server) { bearer -> api.getItem(server.baseUrl, bearer, bookId.toServerItemId()) }
            val planned = planFiles(item.toAudioFileEntries())
            if (planned.isEmpty()) return Step.Failed(NO_FILES)
            val needed = planned.sumOf { it.totalBytes }
            // Other books' files: their whole planned size counts against the limit, what they still need against the free space.
            val others = otherFiles()
            val othersRemaining = others.sumOf { (it.totalBytes - it.doneBytes).coerceAtLeast(0L) }
            when (roomCheck(needed, needed, othersRemaining, others.sumOf { it.totalBytes }, location.freeBytes(), limitBytes())) {
                RoomCheck.NOT_ENOUGH_SPACE -> return Step.Failed(NOT_ENOUGH_SPACE)
                RoomCheck.LIMIT_REACHED -> return Step.Failed(LIMIT_REACHED_TEXT)
                RoomCheck.OK -> Unit
            }
            limitChecked = true

            val download = dao.get(bookId) ?: return Step.Gone
            rows = planned.map { p ->
                DownloadFileEntity(
                    bookId = bookId, ino = p.ino, fileName = p.fileName,
                    path = targetsFor(dir, p.ino, p.fileName).final.path,
                    totalBytes = p.totalBytes, doneBytes = 0L, sortOrder = p.sortOrder,
                    durationMs = p.durationMs, mimeType = p.mimeType
                )
            }
            dao.upsertFiles(rows)
            dao.upsert(download.copy(totalBytes = needed, updatedAt = nowMs()))

            // The book row already holds chapters after a sync; only an empty list is filled in.
            val book = bookDao.getById(bookId)
            val chapters = item.toBookDetails().chapters
            if (book != null && chapters.isNotEmpty() && ChapterJson.decode(book.chaptersJson).isEmpty()) {
                bookDao.updateChapters(bookId, ChapterJson.encode(chapters))
            }
            return null
        }

        /** The server's file list changed: forget the plan and the partial files, so the next run plans again. */
        private suspend fun dropPlan() {
            dao.deleteFiles(bookId)
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                dir.listFiles { f -> f.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
            }
            rows = emptyList()
            current = null
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
            // Progress reaches Room every [persistIntervalMs] or after [persistEveryBytes], whichever comes first.
            val bigStep = Channel<Unit>(Channel.CONFLATED)
            var saved = currentDone
            val saver = launch {
                while (isActive) {
                    withTimeoutOrNull(persistIntervalMs) { bigStep.receive() }
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
                    if (done - saved >= persistEveryBytes) bigStep.trySend(Unit)
                    report()
                }
            } finally {
                saver.cancel()
            }
        }

        /** Updates (never inserts) the file row, so a removed download is not brought back. */
        private suspend fun saveDone(file: DownloadFileEntity, doneBytes: Long) {
            rows = rows.map { if (it.ino == file.ino) it.copy(doneBytes = doneBytes) else it }
            dao.updateFileProgress(bookId, file.ino, doneBytes)
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

    /** Moves the status only while it is one of [from]; false when someone else (pause, removal) changed it first. */
    private suspend fun moveStatus(bookId: String, from: List<DownloadStatus>, to: DownloadStatus, error: String? = null): Boolean =
        dao.updateStatusIf(bookId, from.map { it.name }, to.name, error, nowMs()) > 0

    private suspend fun fail(bookId: String, message: String): BookResult {
        moveStatus(bookId, listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING), DownloadStatus.FAILED, message)
        return BookResult.Failed(message)
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val COVER_FILE = "cover.jpg"
        const val NO_FILES = "This book has no audio files to download"
        const val SERVER_GONE = "The book's server is no longer configured"
        const val BOOK_GONE = "This book is no longer in the library"
        const val FAILED = "The download failed"
    }
}
