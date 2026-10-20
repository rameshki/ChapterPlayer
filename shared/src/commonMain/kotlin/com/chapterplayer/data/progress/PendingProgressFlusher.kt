package com.chapterplayer.data.progress

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.FlushDecision
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.domain.playback.flushDecision
import com.chapterplayer.domain.playback.isPendingExpired
import com.chapterplayer.domain.playback.nextBackoffMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Delivers the queued positions (see [ProgressSyncer]) to their servers. It looks at the queue when the
 * app starts, when the device comes online, when [kick] is called (a position was queued) and when a
 * failed row's retry time arrives. One pass runs at a time. Start it once with [start].
 *
 * Per row: ask the server for its progress; a server that is newer than the row wins (the row is dropped
 * and the server's position stored here), otherwise the row is pushed and removed. A failure keeps the
 * row and schedules the next try ([nextBackoffMs]); a refused sign-in skips the rest of that SERVER's rows for this
 * pass (other servers are still delivered). Rows older than 30 days are dropped ([isPendingExpired]).
 */
class PendingProgressFlusher(
    private val books: BookRepository,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val passLock = Mutex()
    private var started = false
    private var online = true
    private var timer: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            connectivity.isOnline.distinctUntilChanged().collect { isOnline ->
                online = isOnline
                if (isOnline) kick()
            }
        }
        scope.launch {
            for (ignored in kicks) {
                try {
                    pass()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A broken pass must not end the loop; the next trigger tries again.
                }
            }
        }
    }

    /** Asks for a pass soon. Many calls before it starts make one pass. */
    fun kick() {
        kicks.trySend(Unit)
    }

    private suspend fun pass() = passLock.withLock {
        if (!online) return@withLock
        val now = nowMs()
        val blocked = HashSet<String>() // servers whose sign-in was refused during this pass
        for (row in books.pendingProgress()) {
            if (isPendingExpired(row.updatedAtMs, now)) {
                books.deletePending(row.bookId, row.updatedAtMs)
                continue
            }
            if (row.nextAttemptAtMs > now || row.serverId in blocked) continue
            if (!flushOne(row)) blocked += row.serverId
        }
        scheduleNext()
    }

    /** Delivers one row. False when the sign-in of the row's server is refused (its other rows wait for the next pass). */
    private suspend fun flushOne(row: PendingProgress): Boolean {
        val server: ServerProgress? = books.fetchServerProgressOrFailure(row.bookId).getOrElse { error ->
            if (error is SignInRequiredException) return false
            fail(row)
            return true
        }
        when (flushDecision(row, server)) {
            FlushDecision.SEND -> {
                val pushed = try {
                    books.pushProgress(row)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                pushed.onFailure { error ->
                    if (error is CancellationException) throw error
                    if (error is SignInRequiredException) return false
                    fail(row)
                    return true
                }
                books.deletePending(row.bookId, row.updatedAtMs)
            }
            FlushDecision.DROP_SERVER_NEWER -> {
                // The data layer refuses to store the server's position while a row is queued, so the row goes first
                // (only if it is still this row: a position queued again meanwhile keeps its place and the save is refused).
                books.deletePending(row.bookId, row.updatedAtMs)
                try {
                    books.saveServerProgress(row.bookId, server!!)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The next library sync stores it again.
                }
            }
        }
        return true
    }

    private suspend fun BookRepository.fetchServerProgressOrFailure(bookId: String): Result<ServerProgress?> = try {
        fetchServerProgress(bookId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Records the failure on [row] only if it is still the queued one (a newer position has its own turn). */
    private suspend fun fail(row: PendingProgress) {
        val attempts = row.attempts + 1
        books.markPendingAttempt(row.bookId, attempts, nowMs() + nextBackoffMs(attempts), row.updatedAtMs)
    }

    /** Wakes the flusher at the earliest retry time of a row that has to wait. */
    private suspend fun scheduleNext() {
        timer?.cancel()
        val now = nowMs()
        val next = books.pendingProgress().map { it.nextAttemptAtMs }.filter { it > now }.minOrNull() ?: return
        timer = scope.launch {
            delay(next - now)
            kick()
        }
    }
}
