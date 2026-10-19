package com.chapterplayer.data.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.domain.playback.locate
import com.chapterplayer.domain.browse.currentChapterIndex
import com.chapterplayer.domain.playback.sleepRemainingMs
import com.chapterplayer.domain.playback.sleepRemainingToMs
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to [PlaybackService] through a Media3 [MediaController]. It connects when first needed and
 * again if the service has died. The player lives in the service, so every call into the controller
 * happens on the main thread.
 *
 * @param appScope outlives the screens; used to end a server session after the controller is gone.
 */
@UnstableApi
class AndroidPlaybackController(
    private val context: Context,
    private val repository: BookRepository,
    private val appScope: CoroutineScope,
) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectLock = Mutex()
    private var controller: MediaController? = null

    private var session: PlaybackSession? = null
    private var spans = emptyList<com.chapterplayer.domain.playback.TrackSpan>()
    private var error: String? = null

    // Time played (wall clock) since the session began, for the server, and since the sleep timer was set.
    private var listenedMs = 0L
    private var sleepPlayedMs = 0L
    private var lastTickAt: Long? = null
    private var sleepTargetEndMs: Long? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    override val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                accumulate()
                if (player.isPlaying) startTicker() else ticker?.cancel()
            }
            publish()
        }

        override fun onPlayerError(playerError: PlaybackException) {
            error = describe(playerError)
            publish()
        }
    }

    private val sessionListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            // The service died: the next call connects again. Anything that was playing is gone.
            if (this@AndroidPlaybackController.controller === controller) {
                this@AndroidPlaybackController.controller = null
                ticker?.cancel()
                lastTickAt = null
                _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
            }
        }
    }

    // -- Starting a book ---------------------------------------------------------------------

    override suspend fun load(book: AudioBook, startMs: Long) {
        val started = repository.startPlayback(book.id).getOrElse { failure ->
            if (failure is CancellationException) throw failure
            throw PlaybackStartException(safeStartMessage(failure))
        }
        if (started.tracks.isEmpty()) {
            closeSession(started, 0L, 0L)
            throw PlaybackStartException("This book has no audio to play")
        }

        // A new server session replaces the old one, whichever book it was for.
        closeCurrentSession()

        val specs = trackItemSpecs(book, started)
        val newSpans = trackSpans(started)
        val startAt = if (startMs < 0) started.startPositionMs else startMs
        val target = locate(newSpans, startAt)
        session = started
        spans = newSpans
        try {
            withContext(Dispatchers.Main) {
                val player = connect()
                player.setMediaItems(specs.map(::toMediaItem), target?.trackIndex ?: 0, target?.offsetMs ?: 0L)
                player.prepare()
            }
        } catch (e: Exception) {
            session = null
            closeSession(started, 0L, 0L)
            if (e is CancellationException) throw e
            throw PlaybackStartException("Could not start the player")
        }
        error = null
        listenedMs = 0L
        sleepPlayedMs = 0L
        lastTickAt = null
        publish()
    }

    // Artwork is left out for now: the cover address needs the bearer token and a MediaItem must not carry one.
    private fun toMediaItem(spec: TrackItemSpec): MediaItem =
        MediaItem.Builder()
            .setMediaId(spec.mediaId)
            .setUri(spec.url)
            .setMimeType(spec.mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(spec.title)
                    .setArtist(spec.artist)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(
                        Bundle().apply {
                            putLong(EXTRA_START_MS, spec.startMs)
                            putLong(EXTRA_DURATION_MS, spec.durationMs)
                        }
                    )
                    .build()
            )
            .build()

    // -- Transport ---------------------------------------------------------------------------

    // Rewinding after a pause is done by the service, so it applies to every way of resuming.
    override fun play() = withPlayer { player ->
        error = null
        player.play()
    }

    override fun pause() = withPlayer { it.pause() }

    override fun seekTo(positionMs: Long) = withPlayer { seekToBook(it, positionMs) }

    override fun setSpeed(speed: Float) = withPlayer { it.setPlaybackSpeed(speed) }

    // The service skips on the book timeline across files, the same for the notification and the car.
    override fun skipBack() = withPlayer { it.seekBack() }

    override fun skipForward() = withPlayer { it.seekForward() }

    override fun seekToChapter(index: Int) = withPlayer {
        val chapter = _state.value.chapters.getOrNull(index) ?: return@withPlayer
        seekToBook(it, chapter.startMs)
    }

    private fun seekToBook(player: Player, bookPositionMs: Long) {
        val target = locate(spans, bookPositionMs) ?: return
        player.seekTo(target.trackIndex, target.offsetMs)
    }

    // -- Sleep timer -------------------------------------------------------------------------

    override fun setSleepTimer(timer: SleepTimer) {
        accumulate()
        sleepPlayedMs = 0L
        // End of chapter means the chapter playing now: its end is fixed here, not looked up on every tick.
        val now = _state.value
        sleepTargetEndMs = if (timer == SleepTimer.EndOfChapter) {
            currentChapterIndex(now.chapters, now.positionMs)?.let { now.chapters[it].endMs }
        } else null
        _sleepTimer.value = timer
        publish()
    }

    // -- Ticking: position, listened time, sleep timer ---------------------------------------

    private fun startTicker() {
        ticker?.cancel()
        lastTickAt = SystemClock.elapsedRealtime()
        ticker = scope.launch {
            while (isActive) {
                delay(nextTickDelay())
                accumulate()
                publish()
                if (_sleepTimer.value != SleepTimer.Off && _state.value.sleepRemainingMs == 0L) {
                    controller?.pause()
                    _sleepTimer.value = SleepTimer.Off
                    sleepPlayedMs = 0L
                    sleepTargetEndMs = null
                    publish()
                }
            }
        }
    }

    // Tick often enough to stop close to the sleep timer's end.
    private fun nextTickDelay(): Long {
        val remaining = _state.value.sleepRemainingMs ?: return TICK_MS
        return remaining.coerceIn(MIN_TICK_MS, TICK_MS)
    }

    /** Adds the wall-clock time since the last call to the listened time and, when a timer runs, the timer. */
    private fun accumulate() {
        val now = SystemClock.elapsedRealtime()
        val last = lastTickAt
        val playing = controller?.isPlaying == true
        if (last != null) {
            val delta = (now - last).coerceAtLeast(0L)
            listenedMs += delta
            sleepPlayedMs += delta
        }
        lastTickAt = if (playing) now else null
    }

    private fun publish() {
        val player = controller
        val current = session
        if (player == null || current == null) {
            _state.value = PlaybackState(error = error)
            return
        }
        val itemIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val positionInItem = player.currentPosition.coerceAtLeast(0L)
        val speed = player.playbackParameters.speed
        val book = toPlaybackState(
            bookId = current.bookId,
            tracks = spans,
            itemIndex = itemIndex,
            positionInItemMs = positionInItem,
            durationMs = current.durationMs,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            speed = speed,
            chapters = current.chapters,
            sleepRemainingMs = null,
            error = error,
        )
        val target = sleepTargetEndMs
        val remaining = if (_sleepTimer.value == SleepTimer.EndOfChapter) {
            target?.let { sleepRemainingToMs(it, book.positionMs, speed) }
        } else {
            sleepRemainingMs(_sleepTimer.value, sleepPlayedMs, book.positionMs, book.chapters, speed)
        }
        _state.value = book.copy(sleepRemainingMs = remaining)
    }

    // -- Errors ------------------------------------------------------------------------------

    private fun describe(e: PlaybackException): String {
        var signIn = false
        var status: Int? = null
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 10) {
            if (cause is SignInRequiredException) signIn = true
            if (cause is HttpDataSource.InvalidResponseCodeException) status = cause.responseCode
            cause = cause.cause
            depth++
        }
        return safePlaybackError(e.errorCode, status, signIn)
    }

    // -- Connection --------------------------------------------------------------------------

    private suspend fun connect(): MediaController = connectLock.withLock {
        controller?.takeIf { it.isConnected }?.let { return it }
        controller?.let {
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val connected = MediaController.Builder(context, token)
            .setListener(sessionListener)
            .buildAsync()
            .await()
        connected.addListener(playerListener)
        controller = connected
        connected
    }

    // Runs [block] with a connected player on the main thread. Failures show up as the state's error.
    private fun withPlayer(block: (MediaController) -> Unit) {
        scope.launch {
            try {
                block(connect())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Playback is not available"
                publish()
            }
        }
    }

    // -- Ending ------------------------------------------------------------------------------

    private fun closeCurrentSession() {
        val ended = session ?: return
        val position = _state.value.positionMs
        accumulate()
        val listened = listenedMs
        session = null
        closeSession(ended, position, listened)
    }

    // Best effort and off the caller's path: the screen must not wait for the server.
    private fun closeSession(ended: PlaybackSession, positionMs: Long, listened: Long) {
        appScope.launch {
            withContext(NonCancellable) { repository.closePlayback(ended, positionMs, listened) }
        }
    }

    override fun release() {
        closeCurrentSession()
        ticker?.cancel()
        controller?.let {
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
        scope.cancel()
        _state.value = PlaybackState()
    }

    private companion object {
        const val TICK_MS = 500L
        const val MIN_TICK_MS = 20L
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (e: ExecutionException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: CancellationException) {
                continuation.cancel(e)
            }
        },
        MoreExecutors.directExecutor(),
    )
    continuation.invokeOnCancellation { cancel(false) }
}
