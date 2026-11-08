package com.chapterplayer.data.widgets

import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.coverBookId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Redraws the placed widgets of each kind (Glance on Android). */
interface WidgetHost {
    suspend fun redrawNowPlaying()
    suspend fun redrawContinueListening()
    suspend fun redrawListen()
}

/**
 * Redraws the home screen widgets when what they show changes: Now Playing on every change of its state (play,
 * pause, a new chapter or book; never a position tick), Continue Listening at most every [continueQuietMs] while
 * progress, downloads or the network move. The first state is drawn at [start], since the app may have been gone.
 * Listen redraws at once while a book is loaded (play, pause, a chapter, a whole percent) and like Continue Listening
 * while it offers the Resume book; its cover is loaded by [covers] off the redraw and, once it arrives, the widget is
 * drawn again (a redraw never waits for a cover). A failed redraw (no widget host, a launcher that went away) is ignored.
 */
class WidgetRefresher(
    private val states: WidgetStates,
    private val host: WidgetHost,
    private val scope: CoroutineScope,
    private val continueQuietMs: Long = CONTINUE_QUIET_MS,
    private val covers: WidgetCovers = WidgetCovers.None,
) {
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { states.nowPlaying.collect { safely { host.redrawNowPlaying() } } }
        scope.launch { states.continueListening.debounce(continueQuietMs).collect { safely { host.redrawContinueListening() } } }
        scope.launch {
            states.listen
                .debounce { if (it is ListenWidgetState.Controls) 0L else continueQuietMs }
                .collect { safely { host.redrawListen() } }
        }
        // Every new state asks for its book's cover: a cached one answers at once, a failed one is backed off by the loader.
        scope.launch {
            states.listen.collectLatest { state ->
                val bookId = state.coverBookId ?: return@collectLatest
                var arrived = false
                safely { arrived = covers.prefetch(bookId) }
                if (arrived) safely { host.redrawListen() }
            }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The widget will be drawn again on the next change or by the system.
        }
    }

    companion object {
        const val CONTINUE_QUIET_MS = 2_000L
    }
}
