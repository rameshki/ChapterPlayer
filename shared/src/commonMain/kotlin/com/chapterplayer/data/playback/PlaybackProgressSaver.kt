package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.isFinishedAt
import com.chapterplayer.domain.playback.shouldSaveProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Saves listening progress for whichever book plays, whatever screen is open. It watches the controller
 * and saves a book's position when its playback stops, when another book takes over and when the
 * controller is released. Start it once with [start].
 */
class PlaybackProgressSaver(
    private val controller: PlaybackController,
    private val repository: BookRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var previous: PlaybackState? = null
            controller.state.collect { current ->
                val before = previous
                previous = current
                if (before == null || !before.isPlaying) return@collect
                if (current.isPlaying && current.bookId == before.bookId) return@collect
                // Another book took over (or the player went away): the last position seen is the best there is.
                val last = if (current.bookId == before.bookId) current else before
                save(before.bookId, last)
            }
        }
    }

    private suspend fun save(bookId: String?, at: PlaybackState) {
        if (!shouldSaveProgress(bookId, at.positionMs, at.durationMs)) return
        try {
            repository.saveProgress(
                ReadingProgress(bookId!!, at.positionMs, at.durationMs, nowMs(), isFinishedAt(at.positionMs, at.durationMs))
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed local save must not stop the observation; the next stop tries again.
        }
    }
}
