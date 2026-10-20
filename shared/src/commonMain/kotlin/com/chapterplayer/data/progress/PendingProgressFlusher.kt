package com.chapterplayer.data.progress

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.FlushDecision
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.domain.playback.flushDecision
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
 * row and schedules the next try ([nextBackoffMs]); a refused sign-in stops the pass quietly.
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
        for (row in books.pendingProgress()) {
            if (row.nextAttemptAtMs > now) continue
            if (!flushOne(row)) break
        }
        scheduleNext()
    }

    /** Delivers one row. False when the whole pass must stop (the sign-in is refused). */
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
                if (unchanged(row)) books.deletePending(row.bookId)
            }
            FlushDecision.DROP_SERVER_NEWER -> {
                if (unchanged(row)) {
                    // The data layer refuses to store the server's position while a row is queued, so the row goes first.
                    books.deletePending(row.bookId)
                    try {
                        books.saveServerProgress(row.bookId, server!!)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The next library sync stores it again.
                    }
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

    /** False when the position was queued again while this pass worked on [row]: the newer row has its own turn. */
    private suspend fun unchanged(row: PendingProgress): Boolean =
        books.pendingProgress().firstOrNull { it.bookId == row.bookId }?.updatedAtMs == row.updatedAtMs

    private suspend fun fail(row: PendingProgress) {
        if (!unchanged(row)) return
        val attempts = row.attempts + 1
        books.markPendingAttempt(row.bookId, attempts, nowMs() + nextBackoffMs(attempts))
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
