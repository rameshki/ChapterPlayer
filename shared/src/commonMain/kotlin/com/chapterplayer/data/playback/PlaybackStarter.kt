package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Starts a book playing: works out where it begins, loads it, applies the saved speed and plays.
 * Every screen that starts a book (details, Home, the player) goes through here.
 */
class PlaybackStarter(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val settings: AppSettings
) {

    /**
     * Starts [book] at [atMs] when given. Otherwise it starts where this phone left off; with no local
     * progress that is [USE_SERVER_POSITION] (the server says where), and a book finished here starts over.
     * A failure carries a message that is safe to show; cancellation is rethrown.
     */
    suspend fun start(book: AudioBook, atMs: Long? = null): Result<Unit> = try {
        val startMs = atMs ?: savedStartMs(book)
        controller.load(book, startMs)
        controller.setSpeed(settings.playbackSpeed.first())
        controller.play()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlaybackStartException) {
        Result.failure(PlaybackStartException(e.message ?: PlaybackStartException.DEFAULT_MESSAGE))
    } catch (e: Exception) {
        Result.failure(PlaybackStartException(PlaybackStartException.DEFAULT_MESSAGE))
    }

    private suspend fun savedStartMs(book: AudioBook): Long {
        val progress = books.observeProgress(book.id).first() ?: return USE_SERVER_POSITION
        return if (progress.isFinished || progress.positionMs >= book.durationMs) 0L else progress.positionMs.coerceAtLeast(0L)
    }

    companion object {
        /** Passed to [PlaybackController.load] to mean "where the server says". */
        const val USE_SERVER_POSITION = -1L
    }
}
