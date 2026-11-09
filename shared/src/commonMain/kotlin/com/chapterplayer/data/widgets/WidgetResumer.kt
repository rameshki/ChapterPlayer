package com.chapterplayer.data.widgets

import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.domain.widgets.WidgetStartReaction
import com.chapterplayer.domain.widgets.widgetMayResume
import com.chapterplayer.domain.widgets.widgetStartReaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.CoroutineContext

/**
 * Intent extra of the Continue Listening widget's Resume button: the book it showed. The main activity hands it to
 * [WidgetResumer].
 */
const val EXTRA_RESUME_BOOK_ID = "resume_book_id"

/**
 * A widget's Play (Listen, Continue Listening, Now Playing with nothing loaded): starts the book through
 * [PlaybackStarter] (the one load path, so the offline rules apply and their reason is what the person sees).
 * [launchHeadless] is the widgets' path: the book starts without the app opening (the widget flips to its controls by
 * itself); [launch] is the older activity path (`resume_book_id`, kept for widgets drawn by an earlier version), which
 * asks for the full player once the book plays. Only the book the widget offers, or Home's Resume book, is started (see
 * [widgetMayResume]): the activity and the widget broadcasts can be sent by others. One start at a time: a second tap
 * while one runs does nothing. Both run in [scope] (the app's), so nothing cuts a start short; [main] is where the
 * player is called.
 */
class WidgetResumer(
    private val states: WidgetStates,
    private val books: BookRepository,
    private val starter: PlaybackStarter,
    private val openPlayer: OpenPlayerRequest,
    private val scope: CoroutineScope,
    private val main: CoroutineContext = Dispatchers.Main,
) {
    private val busy = Mutex()

    /** Starts [bookId] in the app scope; [onFailure] gets a safe message (never for a tap ignored as a repeat). */
    fun launch(bookId: String, onFailure: (String) -> Unit): Job = scope.launch(main) {
        resume(bookId).onFailure { failure ->
            if (failure !is AlreadyStarting) onFailure(failure.message ?: PlaybackStartException.DEFAULT_MESSAGE)
        }
    }

    /**
     * Starts [bookId] without opening the app. [onFailure] gets what to tell the person (a short toast) and whether the
     * app must open (only to sign in again); a repeated tap while one start runs is not a failure.
     */
    fun launchHeadless(bookId: String, onFailure: (WidgetStartReaction) -> Unit): Job = scope.launch(main) {
        resume(bookId, openPlayerAfter = false).onFailure { failure ->
            if (failure !is AlreadyStarting) onFailure(widgetStartReaction(failure.message))
        }
    }

    /**
     * A failure carries a message that is safe to show; [AlreadyStarting] when another start is under way. With
     * [openPlayerAfter] the full player is asked for once the book plays (the activity path only).
     */
    suspend fun resume(bookId: String, openPlayerAfter: Boolean = true): Result<Unit> {
        if (!busy.tryLock()) return Result.failure(AlreadyStarting())
        try {
            val book = try {
                if (states.mayResume(bookId)) books.observeAllBooks().first().firstOrNull { it.id == bookId } else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return Result.failure(PlaybackStartException(NOTHING_TO_RESUME))
            val started = starter.start(book)
            if (started.isSuccess && openPlayerAfter) openPlayer.request()
            return started
        } finally {
            busy.unlock()
        }
    }

    /** A Resume tap while the previous one is still starting. */
    class AlreadyStarting : Exception("Already starting")

    companion object {
        const val NOTHING_TO_RESUME = "That book cannot be resumed now"
    }
}
