package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.isFinishedAt
import com.chapterplayer.domain.playback.keepsFinishedMark
import com.chapterplayer.domain.playback.shouldSaveNow
import com.chapterplayer.domain.playback.shouldSaveProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Saves listening progress for whichever book plays, whatever screen is open. It watches the controller
 * and saves a book's position every [SAVE_INTERVAL_MS] while it plays, when its playback stops, when
 * another book takes over and when the controller is released. Start it once with [start].
 */
class PlaybackProgressSaver(
    private val controller: PlaybackController,
    private val repository: BookRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** How often playback is looked at to see whether a periodic save is due. */
    private val pollMs: Long = POLL_MS,
) {
    private var started = false

    // The loaded book and since when, for the finished mark rule: a book the user marks as finished while it is loaded stays finished.
    private var trackedBook: String? = null
    private var trackedSince = 0L
    private var lastOwnWrite: Long? = null
    private var keepFinished = false

    private fun track(bookId: String?) {
        if (bookId == trackedBook) return
        trackedBook = bookId
        trackedSince = nowMs()
        lastOwnWrite = null
        keepFinished = false
    }

    fun start() {
        if (started) return
        started = true
        // Periodic save: while playing, the book's position is saved every SAVE_INTERVAL_MS (playing time on the wall clock).
        scope.launch {
            controller.state.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                if (!playing) return@collectLatest
                var lastSavedAt = nowMs()
                while (true) {
                    delay(pollMs)
                    val now = nowMs()
                    if (shouldSaveNow(lastSavedAt, now, isPlaying = true, intervalMs = SAVE_INTERVAL_MS)) {
                        lastSavedAt = now
                        val at = controller.state.value
                        save(at.bookId, at)
                    }
                }
            }
        }
        scope.launch {
            var previous: PlaybackState? = null
            controller.state.collect { current ->
                val before = previous
                previous = current
                if (current.bookId != before?.bookId && (before == null || !before.isPlaying)) track(current.bookId)
                if (before == null || !before.isPlaying) return@collect
                if (current.isPlaying && current.bookId == before.bookId) return@collect
                // Another book took over (or the player went away): the last position seen is the best there is.
                val last = if (current.bookId == before.bookId) current else before
                save(before.bookId, last)
                track(current.bookId)
            }
        }
    }

    private suspend fun save(bookId: String?, at: PlaybackState) {
        if (!shouldSaveProgress(bookId, at.positionMs, at.durationMs)) return
        try {
            track(bookId) // normally already tracked from the state; this covers a save that comes first
            val existing = repository.observeProgress(bookId!!).first()
            keepFinished = keepsFinishedMark(existing, trackedSince, lastOwnWrite, keepFinished)
            val now = nowMs()
            repository.saveProgress(
                ReadingProgress(bookId, at.positionMs, at.durationMs, now, keepFinished || isFinishedAt(at.positionMs, at.durationMs))
            )
            lastOwnWrite = now
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed local save must not stop the observation; the next stop tries again.
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
        const val POLL_MS = 1_000L
    }
}
