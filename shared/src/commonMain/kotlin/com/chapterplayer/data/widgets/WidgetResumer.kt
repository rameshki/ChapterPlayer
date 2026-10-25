package com.chapterplayer.data.widgets

import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.domain.widgets.ContinueWidgetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Intent extra of the Continue Listening widget's Resume button: the book it showed. The main activity hands it to
 * [WidgetResumer].
 */
const val EXTRA_RESUME_BOOK_ID = "resume_book_id"

/**
 * The Continue Listening widget's Resume: starts the book through [PlaybackStarter] (the one load path, so the
 * offline rules apply) and, once it plays, asks for the full player. Only the book the widget offers right now is
 * started: the activity is exported, so an id from anywhere else (or a widget left showing an older book) is refused.
 * Call from the main thread.
 */
class WidgetResumer(
    private val states: WidgetStates,
    private val books: BookRepository,
    private val starter: PlaybackStarter,
    private val openPlayer: OpenPlayerRequest,
) {

    /** A failure carries a message that is safe to show. */
    suspend fun resume(bookId: String): Result<Unit> {
        val book = try {
            val offered = states.continueListening.first()
            if (offered !is ContinueWidgetState.Book || offered.bookId != bookId) null
            else books.observeAllBooks().first().firstOrNull { it.id == bookId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return Result.failure(PlaybackStartException(NOTHING_TO_RESUME))
        val started = starter.start(book)
        if (started.isSuccess) openPlayer.request()
        return started
    }

    companion object {
        const val NOTHING_TO_RESUME = "That book cannot be resumed now"
    }
}
