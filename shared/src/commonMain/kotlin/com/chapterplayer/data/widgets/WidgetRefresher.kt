package com.chapterplayer.data.widgets

import com.chapterplayer.domain.widgets.CoverFetch
import com.chapterplayer.domain.widgets.CoverStep
import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.coverBookId
import com.chapterplayer.domain.widgets.coverNextStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Redraws the placed widgets of each kind (Glance on Android). */
interface WidgetHost {
    suspend fun redrawNowPlaying()
    suspend fun redrawContinueListening()
    suspend fun redrawListen()

    /** Whether a Listen widget is on the home screen (asked once at start; the receiver reports changes). */
    suspend fun listenPlaced(): Boolean
}

/**
 * Redraws the home screen widgets when what they show changes: Now Playing on every change of its state (play,
 * pause, a new chapter or book; never a position tick), Continue Listening at most every [continueQuietMs] while
 * progress, downloads or the network move. The first state is drawn at [start], since the app may have been gone.
 * Listen redraws at once while a book is loaded (play, pause, a chapter, a whole percent) and like Continue Listening
 * while it offers the Resume book. Its state is computed once ([listen], shared with the widget's own session) and
 * only while a Listen widget is placed. Its cover is loaded by [covers] off the redraw and, once it arrives, the widget
 * is drawn again (a redraw never waits for a cover); the cover is asked for again when the state changes, when the
 * network comes back, when the widget asks ([requestCover]: placed, resized, redrawn by the system) and after a failure
 * once the loader's backoff has passed ([coverNextStep]). A failed redraw (no widget host, a launcher that went away)
 * is ignored.
 */
class WidgetRefresher(
    private val states: WidgetStates,
    private val host: WidgetHost,
    private val scope: CoroutineScope,
    private val continueQuietMs: Long = CONTINUE_QUIET_MS,
    private val covers: WidgetCovers = WidgetCovers.None,
) {
    private var started = false

    /** Null until known: the host is asked at [start], the receiver reports changes ([listenPlacementChanged]). */
    private val listenPlaced = MutableStateFlow<Boolean?>(null)
    private val coverRequests = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sharedListen: SharedFlow<ListenWidgetState> =
        listenPlaced
            .flatMapLatest { placed -> if (placed == true) states.listen else emptyFlow() }
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** The Listen widget's state, one computation for the refresher and the widget; nothing while none is placed. */
    val listen: Flow<ListenWidgetState> get() = sharedListen

    /** A Listen widget was placed (or the last one removed). */
    fun listenPlacementChanged(placed: Boolean) {
        listenPlaced.value = placed
    }

    /** The Listen widget is being drawn (placed, resized, a system update): ask for its cover again if it is missing. */
    fun requestCover() {
        coverRequests.update { it + 1 }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { states.nowPlaying.collect { safely { host.redrawNowPlaying() } } }
        scope.launch { states.continueListening.debounce(continueQuietMs).collect { safely { host.redrawContinueListening() } } }
        scope.launch {
            var placed = false
            safely { placed = host.listenPlaced() }
            // A report from the receiver while the host was asked wins.
            listenPlaced.compareAndSet(null, placed)
        }
        scope.launch {
            sharedListen
                .debounce { if (it is ListenWidgetState.Controls) 0L else continueQuietMs }
                .collect { safely { host.redrawListen() } }
        }
        scope.launch {
            combine(sharedListen, states.online, coverRequests) { state, online, _ -> state to online }
                .collectLatest { (state, online) -> fetchCover(state.coverBookId ?: return@collectLatest, online) }
        }
    }

    /** Loads [bookId]'s cover; redraws once when it arrives, retries a failure while online (bounded, see [coverNextStep]). */
    private suspend fun fetchCover(bookId: String, online: Boolean) {
        var attempts = 0
        while (true) {
            attempts++
            var result = CoverFetch.FAILED
            safely { result = covers.prefetch(bookId) }
            when (val step = coverNextStep(result, online, attempts)) {
                CoverStep.Redraw -> return safely { host.redrawListen() }
                CoverStep.Done -> return
                is CoverStep.RetryAfter -> delay(step.delayMs)
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
