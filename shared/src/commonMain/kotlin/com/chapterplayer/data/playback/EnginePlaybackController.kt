package com.chapterplayer.data.playback

import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.AfterInterruption
import com.chapterplayer.domain.playback.IdleAction
import com.chapterplayer.domain.playback.RemoteAction
import com.chapterplayer.domain.playback.RemoteCommand
import com.chapterplayer.domain.playback.ResumeRewind
import com.chapterplayer.domain.playback.RouteChange
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.domain.playback.SleepTimerEngine
import com.chapterplayer.domain.playback.bookPosition
import com.chapterplayer.domain.playback.decideAfterInterruption
import com.chapterplayer.domain.playback.idleAction
import com.chapterplayer.domain.playback.locate
import com.chapterplayer.domain.playback.pausesOnRouteChange
import com.chapterplayer.domain.playback.remoteAction
import com.chapterplayer.domain.playback.resolveStartMs
import com.chapterplayer.domain.playback.seekByTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The title and author of the loaded book, for the lock screen. */
data class BookLabel(val title: String, val author: String)

/**
 * Keeps the app running for a short piece of work while it is in the background (iOS: a background task). [begin]
 * returns the call that ends it; [onExpired] runs on the main thread when the system will not wait any longer.
 */
fun interface BackgroundWork {
    fun begin(onExpired: () -> Unit): () -> Unit

    companion object {
        val None = BackgroundWork { { } }
    }
}

/** Shown when the system stopped the app's work in the background before playback could come back. */
internal const val RECOVERY_CUT_SHORT_TEXT = "Playback paused while reconnecting"

/** Shown when the phone would not give the app the audio (another app holds it). */
internal const val AUDIO_BUSY_TEXT = "The audio is in use by another app"

/**
 * A [PlaybackController] over a platform [AudioEngine] that knows nothing about books (iOS: AVQueuePlayer). It does what
 * Android's controller and its Media3 service do together: one timeline over the book's files (the shared mapping in
 * `domain/playback`), skips of 15 s back and 30 s forward across files, the rewind after a pause ([ResumeRewind]), the
 * sleep timer ([SleepTimerEngine]) on a 2-per-second ticker that also publishes the position, the server session
 * (opened on load, closed when another book loads, after [pausedEndAfterMs] paused or when the downloaded files go), and
 * safe error texts. Audio interruptions, route changes and lock screen commands come in through [onInterruptionBegan],
 * [onInterruptionEnded], [onRouteChange] and [onRemoteCommand], decided by the shared rules.
 *
 * After [pausedEndAfterMs] paused ([idleAction]): with [keepsBookWhenIdle] (iOS) only the server session closes (at the
 * place listening stopped); the book, its place and the lock screen card stay, and the next Play opens a new session by
 * loading the book again there. Without it (as Android's service) the book is forgotten too. [onIdle] then runs (iOS
 * lets other apps' audio go on).
 *
 * Streamed audio gets its token as fixed request headers from [streams], which checks the address first. While playing,
 * the headers are renewed every [HEADER_REFRESH_MS] and when the file changes (a token close to expiry is refreshed
 * first), so the next file is built with a live token. A failed item is checked again, given fresh headers and rebuilt
 * where the engine stands now, at most once every [RECOVERY_SPACING_MS], inside a [background] task; a second failure
 * sooner is shown as the error and Play tries again.
 *
 * A start that does not happen (no audio, a refused check, a format [canPlay] rejects) closes its new session at the
 * session's own start position, so the server's progress is left as it was.
 *
 * Call it from the main thread ([main] is the main dispatcher; tests pass their own).
 */
class EnginePlaybackController(
    private val engine: AudioEngine,
    private val repository: BookRepository,
    private val streams: StreamAuthorizer,
    private val appScope: CoroutineScope,
    private val localFiles: LocalBookFiles,
    /** A file path as the engine's address for it (iOS: a `file://` URL). */
    private val fileUri: (String) -> String,
    /** A steady clock in milliseconds. */
    private val clock: () -> Long,
    private val main: CoroutineDispatcher,
    private val pausedEndAfterMs: Long = PAUSED_END_AFTER_MS,
    private val keepsBookWhenIdle: Boolean = false,
    private val onIdle: () -> Unit = {},
    private val background: BackgroundWork = BackgroundWork.None,
    /** Whether the engine can read a track (iOS: [applePlays]); a book with one it cannot fails with [UNSUPPORTED_AUDIO_TEXT]. */
    private val canPlay: (PlaybackTrack) -> Boolean = { true },
) : PlaybackController, AudioEngine.Listener {

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val loadLock = Mutex()

    private val loaded = CurrentBook()
    private var items: List<EngineItem> = emptyList()
    private var headers: StreamHeaders = StreamHeaders.None
    private var speed = 1.0f
    private var error: String? = null
    /** An item failed and was not recovered: Play tries again where the engine stands. */
    private var failed = false
    private var lastRecoveryAt: Long? = null
    /** Bumped on each load and end, so late work for an older book does nothing. */
    private var generation = 0

    /** What was loaded last, to load it again after the idle close. */
    private var lastBook: AudioBook? = null
    private var lastOrigin: AudioOrigin = AudioOrigin.Server
    /** The idle rule closed the server session; the book is still loaded. */
    private var sessionClosedIdle = false
    private var reopening: Job? = null

    private var lastTickAt: Long? = null
    private var wasPlaying = false
    private var pausedAt: Long? = null
    private val sleep = SleepTimerEngine()
    private val rewind = ResumeRewind()
    private var interruptedWhilePlaying = false
    private var ticker: Job? = null
    private var idleEnd: Job? = null
    private var headersAt: Long? = null
    private var headersIndex = -1

    override val activeSession: PlaybackSession? get() = if (sessionClosedIdle) null else loaded.session

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    override val sleepTimer: StateFlow<SleepTimer> = _sleepTimer.asStateFlow()

    private val _label = MutableStateFlow<BookLabel?>(null)
    /** The loaded book's title and author; null when nothing is loaded. */
    val label: StateFlow<BookLabel?> = _label.asStateFlow()

    init {
        engine.setListener(this)
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
            rollBack(started)
            throw PlaybackStartException("This book has no audio to play")
        }
        if (!started.tracks.all(canPlay)) {
            rollBack(started)
            throw PlaybackStartException(UNSUPPORTED_AUDIO_TEXT)
        }
        val specs = trackItemSpecs(book, started)
        val newSpans = trackSpans(started)
        val newItems = specs.map { EngineItem(it.url, it.mimeType, it.durationMs) }
        val startAt = resolveStartMs(startMs, started.startPositionMs, started.durationMs)
        val target = locate(newSpans, startAt)
        val first = newItems.getOrNull(target?.trackIndex ?: 0)

        // The token for the audio, checked against the server before anything is swapped in.
        val newHeaders = try {
            if (first == null || first.isFile) StreamHeaders.None else streams.prepare(first.url)
        } catch (e: Exception) {
            rollBack(started)
            if (e is CancellationException) throw e
            throw e as? PlaybackStartException ?: PlaybackStartException(PlaybackStartException.DEFAULT_MESSAGE)
        }

        // All or nothing, and not cancellable: if the caller leaves meanwhile the block still finishes, so the engine
        // and the book state agree. Only a real failure rolls back (engine cleared, new session closed).
        val failure = runToEnd(main) {
            try {
                accumulate()
                closeCurrentSession()
                sleep.reset()
                _sleepTimer.value = SleepTimer.Off
                engine.pause()
                engine.setQueue(newItems, newHeaders, target?.trackIndex ?: 0, target?.offsetMs ?: 0L)
                engine.setRate(speed)
                items = newItems
                headers = newHeaders
                headersAt = clock()
                headersIndex = target?.trackIndex ?: 0
                loaded.begin(started, newSpans, startAt)
                _label.value = BookLabel(book.title, book.author)
                lastBook = book
                lastOrigin = origin
                sessionClosedIdle = false
                generation++
                rewind.reset()
                interruptedWhilePlaying = false
                failed = false
                lastRecoveryAt = null
            } catch (e: Exception) {
                loaded.end()
                items = emptyList()
                _label.value = null
                try { engine.clear() } catch (_: Exception) { }
                rollBack(started)
                throw e
            }
        }
        if (failure != null) throw PlaybackStartException("Could not start the player")
        error = null
        lastTickAt = null
        refresh()
    }

    // A start that did not happen: the new session closes where the server said listening was, nothing heard.
    private fun rollBack(started: PlayableBook) {
        started.session?.let { closeSession(it, it.startPositionMs) }
    }

    private suspend fun openSession(book: AudioBook): PlaybackSession =
        repository.startPlayback(book.id).getOrElse { failure ->
            if (failure is CancellationException) throw failure
            throw PlaybackStartException(safeStartMessage(failure))
        }

    /** A downloaded book (see Android's controller): the files on the phone, with a session when one opens. */
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
        val playable = playableFromPhone(book, origin.files, session, fileUri)
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

    // -- Transport ---------------------------------------------------------------------------

    override fun play() {
        // Nothing loaded (the session ended): there is nothing to play; the screen loads the book again.
        if (!loaded.isLoaded) return
        error = null
        if (sessionClosedIdle) {
            reopen()
            return
        }
        if (failed) {
            retry(play = true)
            return
        }
        applyRewind()
        interruptedWhilePlaying = false
        engine.play(speed)
        refresh()
    }

    /** After the idle close: the same book loaded again where it stands (a new session), then played. */
    private fun reopen() {
        if (reopening?.isActive == true) return
        val book = lastBook ?: return
        val origin = lastOrigin
        // The rewind after a pause applies as to any Play (the load itself starts fresh).
        val here = _state.value.positionMs
        val at = rewind.resumed(here, clock()) ?: here
        reopening = scope.launch {
            try {
                load(book, at, origin)
                engine.play(speed)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? PlaybackStartException)?.message ?: PlaybackStartException.DEFAULT_MESSAGE
                refresh()
            }
        }
    }

    override fun pause() {
        reopening?.cancel()
        if (engine.playWhenReady) rewind.paused(clock())
        engine.pause()
        refresh()
    }

    override fun seekTo(positionMs: Long) = seekToBook(positionMs)

    override fun setSpeed(speed: Float) {
        this.speed = speed
        engine.setRate(speed)
        refresh()
    }

    override fun skipBack() = skipBy(-SKIP_BACK_MS)

    override fun skipForward() = skipBy(SKIP_FORWARD_MS)

    override fun seekToChapter(index: Int) {
        val chapter = _state.value.chapters.getOrNull(index) ?: return
        seekToBook(chapter.startMs)
    }

    private fun skipBy(deltaMs: Long) {
        val spans = loaded.spans
        val at = seekByTarget(spans, engine.currentIndex, engine.currentOffsetMs, deltaMs) ?: return
        moveTo(at.trackIndex, at.offsetMs)
    }

    private fun seekToBook(bookPositionMs: Long) {
        val target = locate(loaded.spans, bookPositionMs) ?: return
        moveTo(target.trackIndex, target.offsetMs)
    }

    // A move the listener asked for: "end of chapter" follows it, and a jump while paused is a fresh place (no rewind).
    private fun moveTo(index: Int, offsetMs: Long) {
        engine.seekTo(index, offsetMs)
        val at = bookPosition(loaded.spans, index, offsetMs)
        sleep.userSeeked(at, _state.value.chapters)
        if (!engine.playWhenReady) rewind.seeked()
        refresh()
    }

    private fun applyRewind() {
        if (engine.playWhenReady) return
        val here = bookPosition(loaded.spans, engine.currentIndex, engine.currentOffsetMs)
        val target = rewind.resumed(here, clock()) ?: return
        val at = locate(loaded.spans, target) ?: return
        engine.seekTo(at.trackIndex, at.offsetMs)
    }

    // -- The phone's audio system --------------------------------------------------------------

    /** Another sound took the audio (a call, Siri, an alarm): the system has stopped us. */
    fun onInterruptionBegan() {
        if (!loaded.isLoaded) return
        interruptedWhilePlaying = engine.playWhenReady
        if (interruptedWhilePlaying) {
            // Pressing Play later rewinds as after any pause; resuming by itself does not (as Android's audio focus).
            rewind.paused(clock())
            engine.pause()
        }
        refresh()
    }

    /** The interruption is over; [shouldResume] is the system's word that playing on is expected. */
    fun onInterruptionEnded(shouldResume: Boolean) {
        val was = interruptedWhilePlaying
        interruptedWhilePlaying = false
        if (!loaded.isLoaded) return
        if (decideAfterInterruption(was, shouldResume) == AfterInterruption.RESUME && !failed && !sessionClosedIdle) {
            rewind.reset()
            engine.play(speed)
        }
        refresh()
    }

    /** The audio route changed (headphones, Bluetooth). Headphones that go away pause the book. */
    fun onRouteChange(change: RouteChange) {
        if (!loaded.isLoaded) return
        if (pausesOnRouteChange(change, engine.playWhenReady)) pause() else refresh()
    }

    /**
     * The phone's media services restarted (iOS: every player object is gone). The platform has made a new player;
     * the book is put back where it was, playing on if it was.
     */
    fun onMediaServicesReset() {
        if (!loaded.isLoaded || items.isEmpty()) return
        val play = engine.playWhenReady
        val index = engine.currentIndex
        val offset = engine.currentOffsetMs
        engine.setQueue(items, headers, index, offset)
        engine.setRate(speed)
        if (play) engine.play(speed)
        refresh()
    }

    /** A lock screen, Control Center or headset command. False when there was nothing to do (no book, no chapter). */
    fun onRemoteCommand(command: RemoteCommand): Boolean {
        when (val action = remoteAction(command, _state.value)) {
            RemoteAction.Play -> play()
            RemoteAction.Pause -> pause()
            RemoteAction.SkipForward -> skipForward()
            RemoteAction.SkipBack -> skipBack()
            is RemoteAction.SeekTo -> seekTo(action.positionMs)
            RemoteAction.None -> return false
        }
        return true
    }

    // -- Engine callbacks --------------------------------------------------------------------

    override fun onChanged() = refresh()

    override fun onEnded() {
        // The end is not a pause to rewind from.
        rewind.reset()
        refresh()
    }

    override fun onItemFailed(index: Int, failure: EngineFailure) {
        if (!loaded.isLoaded) return
        if (failure == EngineFailure.AUDIO_SESSION) {
            // Nothing is wrong with the audio: show why, and the next Play simply asks again.
            error = AUDIO_BUSY_TEXT
            engine.pause()
            refresh()
            return
        }
        val item = items.getOrNull(index) ?: return
        val now = clock()
        val spaced = lastRecoveryAt?.let { now - it >= RECOVERY_SPACING_MS } ?: true
        if (item.isFile || !spaced) {
            fail(if (item.isFile) LOCAL_FILE_FAILED_TEXT else failureText(failure))
            return
        }
        lastRecoveryAt = now
        retry(play = engine.playWhenReady)
    }

    /**
     * Checks the address of the item the engine stands on now (after a failure the listener may have moved) with fresh
     * headers and rebuilds it there, inside a background task so a failure while the phone is locked can recover.
     */
    private fun retry(play: Boolean) {
        val index = engine.currentIndex
        val item = items.getOrNull(index) ?: return
        val offset = engine.currentOffsetMs
        val gen = generation
        failed = false
        var finished = false
        lateinit var job: Job
        val end = background.begin(onExpired = {
            // The system will not wait longer: stop cleanly where we are; Play tries again.
            if (!finished && gen == generation) {
                job.cancel()
                fail(RECOVERY_CUT_SHORT_TEXT)
            }
        })
        job = scope.launch {
            try {
                val fresh = try {
                    streams.prepare(item.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (gen == generation) fail((e as? PlaybackStartException)?.message ?: PLAYBACK_FAILED_TEXT)
                    return@launch
                }
                if (gen != generation) return@launch
                headers = fresh
                headersAt = clock()
                engine.reload(fresh, index, offset)
                if (play) engine.play(speed)
                refresh()
            } finally {
                finished = true
                end()
            }
        }
        refresh()
    }

    private fun fail(text: String) {
        error = text
        failed = true
        engine.pause()
        refresh()
    }

    private fun failureText(failure: EngineFailure): String = when (failure) {
        EngineFailure.NETWORK -> CANNOT_REACH_TEXT
        EngineFailure.UNAUTHORIZED -> SIGN_IN_AGAIN_TEXT
        EngineFailure.FORBIDDEN -> NOT_ALLOWED_TEXT
        EngineFailure.NOT_FOUND -> AUDIO_NOT_FOUND_TEXT
        EngineFailure.AUDIO_SESSION -> AUDIO_BUSY_TEXT
        EngineFailure.OTHER -> PLAYBACK_FAILED_TEXT
    }

    // -- Headers ----------------------------------------------------------------------------------

    // While playing a streamed book: every HEADER_REFRESH_MS and when the file changes, the token is renewed if it is
    // close to expiry and the next file is built with it. Failures keep the headers in use.
    private fun renewHeadersIfDue() {
        if (headers.isEmpty || !engine.isPlaying) return
        val now = clock()
        val index = engine.currentIndex
        val due = index != headersIndex || headersAt?.let { now - it >= HEADER_REFRESH_MS } != false
        if (!due) return
        headersAt = now
        headersIndex = index
        val url = items.getOrNull(index)?.url ?: return
        val gen = generation
        scope.launch {
            val fresh = streams.currentHeaders(url) ?: return@launch
            if (gen != generation || fresh == headers) return@launch
            headers = fresh
            engine.updateHeaders(fresh)
        }
    }

    // -- Sleep timer -------------------------------------------------------------------------

    override fun setSleepTimer(timer: SleepTimer) {
        accumulate()
        val now = _state.value
        sleep.set(timer, now.positionMs, now.chapters)
        _sleepTimer.value = timer
        refresh()
    }

    // -- Ticking: position, played time, sleep timer, idle end ---------------------------------

    /** Notes a change of playing, keeps the ticker and the idle timer right, and publishes. */
    private fun refresh() {
        val playing = loaded.isLoaded && engine.isPlaying
        if (playing != wasPlaying) {
            accumulate()
            wasPlaying = playing
        }
        val wantsTicks = loaded.isLoaded && (engine.playWhenReady || playing)
        if (wantsTicks && ticker?.isActive != true) startTicker()
        if (!wantsTicks) ticker?.cancel()
        updateIdleEnd(wantsTicks)
        publish()
    }

    private fun startTicker() {
        ticker = scope.launch {
            while (isActive) {
                delay(nextTickDelay())
                accumulate()
                publish()
                val now = _state.value
                if (sleep.tick(now.positionMs, now.chapters, now.speed)) {
                    _sleepTimer.value = SleepTimer.Off
                    pause()
                }
                renewHeadersIfDue()
                // Catches a change the engine did not report (and stops the ticker once paused).
                refresh()
            }
        }
    }

    // Tick often enough to stop close to the sleep timer's end.
    private fun nextTickDelay(): Long {
        val remaining = _state.value.sleepRemainingMs ?: return TICK_MS
        return remaining.coerceIn(MIN_TICK_MS, TICK_MS)
    }

    /** Adds the time since the last call to the sleep timer's played time while sound was coming out. */
    private fun accumulate() {
        val now = clock()
        val last = lastTickAt
        if (last != null) sleep.addPlayed((now - last).coerceAtLeast(0L))
        lastTickAt = if (loaded.isLoaded && engine.isPlaying) now else null
    }

    // The idle timer: started when playing stops, cancelled when it starts; the rule is checked again when it fires.
    private fun updateIdleEnd(active: Boolean) {
        if (active || !loaded.isLoaded) {
            idleEnd?.cancel()
            idleEnd = null
            pausedAt = null
            return
        }
        if (sessionClosedIdle) return
        if (pausedAt == null) pausedAt = clock()
        if (idleEnd?.isActive != true) {
            idleEnd = scope.launch {
                delay(pausedEndAfterMs)
                onIdleTimer()
            }
        }
    }

    private fun onIdleTimer() {
        idleEnd = null
        val since = pausedAt ?: return
        when (idleAction(engine.playWhenReady || reopening?.isActive == true, clock() - since, pausedEndAfterMs, keepsBookWhenIdle)) {
            IdleAction.NOTHING -> if (loaded.isLoaded && !engine.playWhenReady) {
                // Not long enough yet by the clock: look again when it will be.
                val left = (pausedEndAfterMs - (clock() - since)).coerceAtLeast(MIN_TICK_MS)
                idleEnd = scope.launch {
                    delay(left)
                    onIdleTimer()
                }
            }
            IdleAction.CLOSE_SESSION -> {
                closeIdleSession()
                onIdle()
            }
            IdleAction.END_ALL -> {
                endSession()
                onIdle()
            }
        }
    }

    // Only the server hears that listening stopped; the book stays loaded and shown.
    private fun closeIdleSession() {
        val session = loaded.session ?: return
        sessionClosedIdle = true
        closeSession(session, _state.value.positionMs)
        publish()
    }

    private fun publish() {
        val current = loaded.book
        if (current == null) {
            _state.value = PlaybackState(error = error)
            return
        }
        val book = toPlaybackState(
            bookId = current.bookId,
            tracks = loaded.spans,
            itemIndex = engine.currentIndex.coerceAtLeast(0),
            positionInItemMs = engine.currentOffsetMs.coerceAtLeast(0L),
            durationMs = current.durationMs,
            isPlaying = engine.isPlaying,
            isBuffering = engine.isBuffering || reopening?.isActive == true,
            speed = speed,
            chapters = current.chapters,
            sleepRemainingMs = null,
            error = error,
        ).copy(playWhenReady = engine.playWhenReady || reopening?.isActive == true)
        loaded.seen(book.positionMs)
        val remaining = sleep.remainingMs(book.positionMs, book.chapters, speed)
        _state.value = book.copy(sleepRemainingMs = remaining)
    }

    // -- Ending ------------------------------------------------------------------------------

    private fun closeCurrentSession() {
        val wasClosed = sessionClosedIdle
        sessionClosedIdle = false
        val (ended, position) = loaded.end() ?: return
        generation++
        _label.value = null
        // A session the idle rule closed already was told where listening stopped.
        if (!wasClosed) ended.session?.let { closeSession(it, position) }
    }

    // Best effort and off the caller's path. No listening time: ProgressSyncer reports it (see Android's controller).
    private fun closeSession(ended: PlaybackSession, positionMs: Long) {
        appScope.launch {
            withContext(NonCancellable) { repository.closePlayback(ended, positionMs, 0L) }
        }
    }

    /** Ends the server session now, with the last position, and forgets the book: the next Play loads it again. */
    fun endSession() {
        accumulate()
        closeCurrentSession()
        reopening?.cancel()
        ticker?.cancel()
        idleEnd?.cancel()
        idleEnd = null
        pausedAt = null
        lastTickAt = null
        wasPlaying = false
        error = null
        failed = false
        sleep.reset()
        _sleepTimer.value = SleepTimer.Off
        items = emptyList()
        headers = StreamHeaders.None
        engine.pause()
        engine.clear()
        _state.value = PlaybackState()
    }

    override fun releaseLocalFiles(bookId: String) {
        scope.launch {
            val book = loaded.book ?: return@launch
            if (book.bookId == bookId && playsFromPhone(book)) {
                endSession()
                onIdle()
            }
        }
    }

    override fun release() {
        closeCurrentSession()
        ticker?.cancel()
        idleEnd?.cancel()
        engine.setListener(null)
        engine.release()
        scope.cancel()
        _state.value = PlaybackState()
    }

    companion object {
        const val SKIP_BACK_MS = 15_000L
        const val SKIP_FORWARD_MS = 30_000L
        const val TICK_MS = 500L
        const val MIN_TICK_MS = 20L
        /** As long as Android's service waits before it stops itself. */
        const val PAUSED_END_AFTER_MS = 10 * 60 * 1000L
        /** A failed item is rebuilt at most this often; a second failure sooner is shown as the error. */
        const val RECOVERY_SPACING_MS = 60_000L
        /** How often the stream headers are renewed while playing (an ABS token close to expiry is refreshed first). */
        const val HEADER_REFRESH_MS = 10 * 60 * 1000L
    }
}
