package com.chapterplayer.data.bookmarks

import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.HistoryEntry
import com.chapterplayer.domain.playback.jumpedFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Remembers the position a listener left when the player jumps (a slider drag, a chapter jump, a seek), so it can be
 * gone back to. It watches the player state: two emissions of the same book that do not follow from normal playback
 * ([jumpedFrom]) record the earlier position. A new book, no book, or a player that is not ready (no duration yet)
 * only reset the baseline, so loading a book never looks like a jump.
 */
class HistoryRecorder(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Baseline(val bookId: String, val positionMs: Long, val atMs: Long)

    private var started = false
    private var baseline: Baseline? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            controller.state.collect { state ->
                try {
                    handle(state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failure to record must not end the recorder.
                }
            }
        }
    }

    private suspend fun handle(state: PlaybackState) {
        val bookId = state.bookId
        if (bookId == null || state.durationMs <= 0L) {
            baseline = null
            return
        }
        val now = nowMs()
        val previous = baseline
        baseline = Baseline(bookId, state.positionMs, now)
        if (previous == null || previous.bookId != bookId) return
        val left = jumpedFrom(previous.positionMs, previous.atMs, state.positionMs, now, state.speed, state.isPlaying) ?: return
        books.recordHistory(HistoryEntry(bookId, left, now))
    }
}
