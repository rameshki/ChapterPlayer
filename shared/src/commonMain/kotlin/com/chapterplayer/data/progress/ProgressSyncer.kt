package com.chapterplayer.data.progress

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.shouldSaveProgress
import com.chapterplayer.domain.playback.shouldSyncNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Tells the book's server where listening is, whatever screen is open: every [SYNC_INTERVAL_MS] while a
 * book plays, and once more when playback pauses, another book takes over or the player is released.
 * Whatever the server does not get is queued ([BookRepository.queueProgress]) for the
 * [PendingProgressFlusher]. It never throws (cancellation aside) and never waits on playback. Start it once with [start].
 *
 * @param onQueued called after a position was queued, so the flusher can look at the queue.
 */
class ProgressSyncer(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onQueued: () -> Unit = {},
    /** How often playback is looked at to see whether a sync is due. */
    private val pollMs: Long = POLL_MS,
) {
    private sealed interface Event {
        data class Changed(val state: PlaybackState) : Event
        data object Tick : Event
    }

    private var started = false

    // Only the one collector in [start] touches these, so they need no lock.
    private var previous: PlaybackState? = null
    private var previousSession: PlaybackSession? = null
    private var lastSyncedAt = 0L
    private val known = mutableMapOf<String, AudioBook>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val ticks: Flow<Event> = flow {
                while (true) {
                    delay(pollMs)
                    emit(Event.Tick)
                }
            }
            merge(controller.state.map<PlaybackState, Event> { Event.Changed(it) }, ticks).collect { event ->
                try {
                    when (event) {
                        is Event.Changed -> onChanged(event.state)
                        Event.Tick -> onTick()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing here may end the observation; the next event tries again.
                }
            }
        }
    }

    private suspend fun onChanged(current: PlaybackState) {
        val before = previous
        previous = current
        val session = controller.activeSession?.takeIf { it.bookId == current.bookId }
        if (before != null && before.isPlaying) {
            val sameBook = current.bookId == before.bookId
            if (!current.isPlaying || !sameBook) {
                // Paused, stopped, released or another book took over: the last position seen is the best there is.
                val last = if (sameBook) current else before
                val oldSession = (if (sameBook) session else null) ?: previousSession?.takeIf { it.bookId == before.bookId }
                syncNow(before.bookId, last, oldSession)
            }
        }
        val continuing = before != null && before.isPlaying && before.bookId == current.bookId
        if (current.isPlaying && !continuing) lastSyncedAt = nowMs()
        previousSession = session ?: if (current.bookId == before?.bookId) previousSession else null
    }

    private suspend fun onTick() {
        val current = controller.state.value
        if (!current.isPlaying) return
        val now = nowMs()
        if (!shouldSyncNow(lastSyncedAt, now, isPlaying = true, intervalMs = SYNC_INTERVAL_MS)) return
        val session = controller.activeSession?.takeIf { it.bookId == current.bookId }
        if (session != null) previousSession = session
        syncNow(current.bookId, current, session ?: previousSession?.takeIf { it.bookId == current.bookId })
    }

    /** The book's server and type, read from the library once; null when the book is not in it. */
    private suspend fun findBook(bookId: String): AudioBook? {
        known[bookId]?.let { return it }
        val found = try {
            books.observeAllBooks().first().firstOrNull { it.id == bookId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (found != null) known[bookId] = found
        return found
    }

    /** One report of [at] for [bookId] against [session]; a failure of any kind queues it instead. */
    private suspend fun syncNow(bookId: String?, at: PlaybackState, session: PlaybackSession?) {
        val now = nowMs()
        val listenedMs = (now - lastSyncedAt).coerceIn(0L, MAX_LISTENED_MS)
        lastSyncedAt = now
        if (!shouldSaveProgress(bookId, at.positionMs, at.durationMs)) return
        val book = findBook(bookId!!) ?: return
        if (book.source != ServerSource.ABS) return
        val sent = session != null && try {
            books.syncPlayback(session, at.positionMs, listenedMs).isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (sent) return
        try {
            books.queueProgress(
                PendingProgress(book.id, book.serverId, at.positionMs, at.durationMs, listenedMs, updatedAtMs = now)
            )
            onQueued()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The local progress is saved separately; losing one queued report only delays the server.
        }
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 10_000L
        const val POLL_MS = 1_000L
        const val MAX_LISTENED_MS = 60_000L
    }
}
