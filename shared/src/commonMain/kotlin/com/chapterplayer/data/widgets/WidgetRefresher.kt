package com.chapterplayer.data.widgets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Redraws the placed widgets of each kind (Glance on Android). */
interface WidgetHost {
    suspend fun redrawNowPlaying()
    suspend fun redrawContinueListening()
}

/**
 * Redraws the home screen widgets when what they show changes: Now Playing on every change of its state (play,
 * pause, a new chapter or book; never a position tick), Continue Listening at most every [continueQuietMs] while
 * progress, downloads or the network move. The first state is drawn at [start], since the app may have been gone.
 * A failed redraw (no widget host, a launcher that went away) is ignored.
 */
class WidgetRefresher(
    private val states: WidgetStates,
    private val host: WidgetHost,
    private val scope: CoroutineScope,
    private val continueQuietMs: Long = CONTINUE_QUIET_MS,
) {
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { states.nowPlaying.collect { safely { host.redrawNowPlaying() } } }
        scope.launch { states.continueListening.debounce(continueQuietMs).collect { safely { host.redrawContinueListening() } } }
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
