package com.chapterplayer.data.playback

import com.chapterplayer.domain.playback.TrackSpan
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The book the controller has loaded: its server session, where its files sit on the timeline and the
 * last position seen. Nothing is loaded until [begin], and [end] forgets everything, so a controller whose
 * session ended reports "no book" and the next Resume has to load again.
 */
class CurrentBook {
    var session: PlaybackSession? = null
        private set
    var spans: List<TrackSpan> = emptyList()
        private set
    var lastPositionMs: Long = 0L
        private set

    val isLoaded: Boolean get() = session != null

    fun begin(session: PlaybackSession, spans: List<TrackSpan>) {
        this.session = session
        this.spans = spans
        lastPositionMs = 0L
    }

    /** Notes the position of the loaded book; ignored when nothing is loaded. */
    fun seen(positionMs: Long) {
        if (session != null) lastPositionMs = positionMs
    }

    /** Forgets the book. Returns its session and last position, or null when nothing was loaded. */
    fun end(): Pair<PlaybackSession, Long>? {
        val ended = session ?: return null
        val position = lastPositionMs
        session = null
        spans = emptyList()
        lastPositionMs = 0L
        return ended to position
    }
}

/**
 * Runs [block] to the end even if the caller is cancelled while it runs, and returns the failure instead of
 * throwing it (null on success). A cancelled caller still gets its CancellationException when this returns,
 * after the block has finished and any state it set is consistent.
 */
suspend fun runToEnd(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> Unit): Exception? =
    withContext(NonCancellable + context) {
        try {
            block()
            null
        } catch (e: Exception) {
            e
        }
    }
