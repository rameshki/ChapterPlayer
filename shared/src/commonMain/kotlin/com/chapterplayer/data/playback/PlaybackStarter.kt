package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.playback.startPositionFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Starts a book playing: works out where it begins, loads it, applies the saved speed and plays.
 * Every screen that starts a book (details, Home) goes through here.
 */
class PlaybackStarter(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val settings: AppSettings
) {

    /**
     * Starts [book] at [atMs] when given. Otherwise it starts where this phone left off
     * (see [startPositionFor]); a book that is already loaded in the player is never reloaded: it plays on
     * from where it is (nothing happens when it is already playing). A failure carries a message that is safe to show; cancellation is rethrown.
     * The saved speed is a nicety: if it cannot be read or applied the book still plays.
     */
    suspend fun start(book: AudioBook, atMs: Long? = null): Result<Unit> = try {
        if (atMs == null && controller.state.value.bookId == book.id) {
            // Already loaded: carry on from the live position. A reload would restart from the last saved one.
            continueLoaded()
            return Result.success(Unit)
        }
        val startMs = atMs ?: startPositionFor(books.observeProgress(book.id).first(), book.durationMs)
        controller.load(book, startMs)
        applySavedSpeed()
        controller.play()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlaybackStartException) {
        Result.failure(PlaybackStartException(e.message ?: PlaybackStartException.DEFAULT_MESSAGE))
    } catch (_: Exception) {
        Result.failure(PlaybackStartException(PlaybackStartException.DEFAULT_MESSAGE))
    }

    private fun continueLoaded() {
        val state = controller.state.value
        val running = state.isPlaying || (state.isBuffering && state.playWhenReady)
        if (!running) controller.play()
    }

    private suspend fun applySavedSpeed() {
        val speed = try {
            settings.playbackSpeed.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            1.0f
        }
        try {
            controller.setSpeed(speed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The book is loaded; it plays at whatever speed the player has.
        }
    }
}
