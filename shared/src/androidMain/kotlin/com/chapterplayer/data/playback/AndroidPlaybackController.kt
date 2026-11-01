package com.chapterplayer.data.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.domain.playback.SleepTimerEngine
import com.chapterplayer.domain.playback.resolveStartMs
import com.chapterplayer.domain.playback.bookPosition
import com.chapterplayer.domain.playback.locate
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
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
    private val localFiles: LocalBookFiles,
) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectLock = Mutex()
    private val loadLock = Mutex()
    private var controller: MediaController? = null

    private val loaded = CurrentBook()
    private val session get() = loaded.session
    private val spans get() = loaded.spans
    private var error: String? = null

    // Time played (wall clock) since the session began, for the server, and since the sleep timer was set.
    private var lastTickAt: Long? = null
    private val sleep = SleepTimerEngine()
    private var ticker: Job? = null

    override val activeSession: PlaybackSession? get() = session

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

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // The listener moved the position (seek, skip, chapter jump): "end of chapter" follows them.
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                val at = bookPosition(spans, newPosition.mediaItemIndex, newPosition.positionMs)
                sleep.userSeeked(at, _state.value.chapters)
            }
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
                // Give the server what was heard, then forget the book: the new player is empty.
                accumulate()
                closeCurrentSession()
                lastTickAt = null
                error = null
                _state.value = PlaybackState()
            }
        }
    }

    // -- Starting a book ---------------------------------------------------------------------

    // One load at a time: a second waits for the first, so the last request is the one heard.
    override suspend fun load(book: AudioBook, startMs: Long, origin: AudioOrigin) =
        loadLock.withLock { loadLocked(book, startMs, origin) }

    private suspend fun loadLocked(book: AudioBook, startMs: Long, origin: AudioOrigin) {
        val started = when (origin) {
            AudioOrigin.Server -> streamed(openSession(book))
            is AudioOrigin.Phone -> fromPhone(book, origin)
        }
        if (started.tracks.isEmpty()) {
            // A start that does not happen leaves the server's progress where it was: close at the session's own start.
            started.session?.let { closeSession(it, it.startPositionMs) }
            throw PlaybackStartException("This book has no audio to play")
        }

        val specs = trackItemSpecs(book, started)
        val newSpans = trackSpans(started)
        val startAt = resolveStartMs(startMs, started.startPositionMs, started.durationMs)
        val target = locate(newSpans, startAt)

        // All or nothing, and not cancellable: if the caller leaves meanwhile (Back during "Starting") the
        // block still finishes, so the player and the book state agree. The new session stays open and
        // published; only a real failure rolls back completely (player cleared, new session closed).
        val failure = runToEnd(Dispatchers.Main) {
            val player = try {
                withTimeout(CONNECT_TIMEOUT_MS) { connect() }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("The player did not answer")
            }
            try {
                // A new server session replaces the old one, whichever book it was for.
                accumulate()
                closeCurrentSession()
                // A timer belongs to the book it was set for.
                sleep.reset()
                _sleepTimer.value = SleepTimer.Off
                player.pause() // the old book stops (and is saved) before the new one is swapped in
                player.setMediaItems(specs.map(::toMediaItem), target?.trackIndex ?: 0, target?.offsetMs ?: 0L)
                player.prepare()
                // Only now does the controller name the new book, so no state mixes the two.
                loaded.begin(started, newSpans, startAt)
            } catch (e: Exception) {
                loaded.end()
                try { player.clearMediaItems() } catch (_: Exception) { }
                started.session?.let { closeSession(it, it.startPositionMs) }
                throw e
            }
        }
        if (failure != null) throw PlaybackStartException("Could not start the player")
        error = null
        lastTickAt = null
        publish()
    }

    private suspend fun openSession(book: AudioBook): PlaybackSession =
        repository.startPlayback(book.id).getOrElse { failure ->
            if (failure is CancellationException) throw failure
            throw PlaybackStartException(safeStartMessage(failure))
        }

    /**
     * A downloaded book: the audio is always the files on the phone. Online a session is opened as well, so listening
     * syncs live; when that fails the book plays without one and the progress queue reports it later.
     */
    private suspend fun fromPhone(book: AudioBook, origin: AudioOrigin.Phone): PlayableBook {
        var sessionFailure: Throwable? = null
        val session = if (origin.openSession) {
            repository.startPlayback(book.id).getOrElse { failure ->
                if (failure is CancellationException) throw failure
                sessionFailure = failure
                null
            }
        } else {
            null
        }
        val playable = playableFromPhone(book, origin.files, session, ::fileUri)
            // The files' lengths are not known (a download from before they were recorded): stream it this time,
            // or say why not. Online the server's own failure is the reason; offline the lengths are.
            ?: return session?.let(::streamed)
                ?: throw PlaybackStartException(sessionFailure?.let(::safeStartMessage) ?: NEEDS_ONE_ONLINE_PLAY_TEXT)
        val learnt = durationsToRemember(origin.files, session)
        if (learnt.isNotEmpty()) {
            appScope.launch {
                try {
                    localFiles.saveDurations(book.id, learnt)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Only a nicety for playing offline later.
                }
            }
        }
        return playable
    }

    private fun fileUri(path: String): String = Uri.fromFile(File(path)).toString()

    // Artwork is left out for now: the cover address needs the bearer token and a MediaItem must not carry one.
    private fun toMediaItem(spec: TrackItemSpec): MediaItem =
        MediaItem.Builder()
            .setMediaId(spec.mediaId)
            .setUri(spec.url)
            // Unknown: the player works the format out from the file.
            .setMimeType(spec.mimeType.ifEmpty { null })
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
        // Nothing loaded (the session ended): there is nothing to play; the screen loads the book again.
        if (!loaded.isLoaded) return@withPlayer
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
        val now = _state.value
        sleep.set(timer, now.positionMs, now.chapters)
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
                val now = _state.value
                if (sleep.tick(now.positionMs, now.chapters, now.speed)) {
                    controller?.pause()
                    _sleepTimer.value = SleepTimer.Off
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
            sleep.addPlayed(delta)
        }
        lastTickAt = if (playing) now else null
    }

    private fun publish() {
        val player = controller
        val current = loaded.book
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
        ).copy(playWhenReady = player.playWhenReady)
        loaded.seen(book.positionMs)
        val remaining = sleep.remainingMs(book.positionMs, book.chapters, speed)
        _state.value = book.copy(sleepRemainingMs = remaining)
    }

    // -- Errors ------------------------------------------------------------------------------

    // Over the session connection only the error code and each cause's class name and message are
    // certain to arrive, so the text is chosen from those (see describeError).
    private fun describe(e: PlaybackException): String {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(10).map {
            ErrorLink(
                className = it.javaClass.name,
                message = it.message,
                httpStatus = (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
            )
        }.toList()
        return describeError(e.errorCode, chain)
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
        val (ended, position) = loaded.end() ?: return
        accumulate()
        // A downloaded book playing offline has no session to close; its position is in the progress queue.
        ended.session?.let { closeSession(it, position) }
    }

    // Best effort and off the caller's path: the screen must not wait for the server.
    // The close reports NO listening time: ProgressSyncer already reports it in its periodic and final syncs,
    // and Audiobookshelf adds the time of a close on top of the syncs, so sending it again would count it twice.
    private fun closeSession(ended: PlaybackSession, positionMs: Long) {
        appScope.launch {
            withContext(NonCancellable) { repository.closePlayback(ended, positionMs, 0L) }
        }
    }

    /** Ends the server session now (the service stopped itself), with the last position. */
    fun endSession() {
        accumulate()
        closeCurrentSession()
        // Back to "nothing loaded": the next Resume loads the book again (fresh session, saved position).
        ticker?.cancel()
        lastTickAt = null
        error = null
        sleep.reset()
        _sleepTimer.value = SleepTimer.Off
        controller?.takeIf { it.isConnected }?.let {
            it.pause()
            it.clearMediaItems()
        }
        _state.value = PlaybackState()
    }

    override fun releaseLocalFiles(bookId: String) {
        scope.launch {
            val book = loaded.book ?: return@launch
            if (book.bookId == bookId && playsFromPhone(book)) endSession()
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
        const val CONNECT_TIMEOUT_MS = 15_000L
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
