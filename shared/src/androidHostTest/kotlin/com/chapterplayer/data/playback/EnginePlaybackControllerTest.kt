package com.chapterplayer.data.playback

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.downloads.LocalFile
import com.chapterplayer.data.downloads.NoLocalBookFiles
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.domain.playback.RemoteCommand
import com.chapterplayer.domain.playback.RouteChange
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS player's book logic (timeline, skips, rewind, interruptions, remote commands, recovery) over a fake engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnginePlaybackControllerTest {

    private val base = "https://abs.example.test"
    private val server = ServerConfig(id = "s1", source = ServerSource.ABS, name = "ABS", baseUrl = base, token = "tok", refreshToken = "r")
    private val chapters = listOf(Chapter(0, "One", 0L, 100_000L), Chapter(1, "Two", 100_000L, 250_000L))
    private val book: AudioBook = testBook("b1").copy(durationMs = 250_000L, chapters = chapters)

    // Two files on one timeline: 0-100 s and 100-250 s.
    private val session = PlaybackSession(
        sessionId = "sess", serverId = "s1", bookId = "b1", durationMs = 250_000L, startPositionMs = 0L,
        tracks = listOf(
            PlaybackTrack(0, 0L, 100_000L, "audio/mpeg", "$base/api/items/b1/file/1"),
            PlaybackTrack(1, 100_000L, 150_000L, "audio/mpeg", "$base/api/items/b1/file/2"),
        ),
        chapters = chapters,
    )

    /** Remembers what the controller asked for; positions only move when told. */
    class FakeEngine : AudioEngine {
        var heard: AudioEngine.Listener? = null
        var items: List<EngineItem> = emptyList()
        var headers: StreamHeaders = StreamHeaders.None
        var reloads = 0
        var queues = 0
        val updatedHeaders = mutableListOf<StreamHeaders>()
        var speed = 1.0f
        override var currentIndex = 0
        override var currentOffsetMs = 0L
        override var playWhenReady = false
        var ready = true
        override val isPlaying: Boolean get() = playWhenReady && ready && !ended
        override val isBuffering: Boolean get() = playWhenReady && !ready
        override var ended = false
        override fun setListener(listener: AudioEngine.Listener?) { heard = listener }
        override fun setQueue(items: List<EngineItem>, headers: StreamHeaders, index: Int, offsetMs: Long) {
            queues++; this.items = items; this.headers = headers; currentIndex = index; currentOffsetMs = offsetMs; ended = false
        }
        override fun updateHeaders(headers: StreamHeaders) { updatedHeaders += headers; this.headers = headers }
        override fun reload(headers: StreamHeaders, index: Int, offsetMs: Long) {
            reloads++; this.headers = headers; currentIndex = index; currentOffsetMs = offsetMs
        }
        override fun clear() { items = emptyList(); currentIndex = 0; currentOffsetMs = 0L }
        override fun play(rate: Float) { speed = rate; if (!ended) playWhenReady = true }
        override fun pause() { playWhenReady = false }
        override fun setRate(rate: Float) { speed = rate }
        override fun seekTo(index: Int, offsetMs: Long) { currentIndex = index; currentOffsetMs = offsetMs; ended = false }
        override fun release() {}
    }

    /** Hands out "Bearer tok", and "Bearer fresh" after a 401, like a refresh; a second 401 is sign in again. */
    private class RefreshingAuthorizer : AbsAuthorizer {
        var refreshes = 0
        /** The token handed out first (a test changes it to act as a refresh before expiry). */
        var current = "tok"
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            try {
                call("Bearer $current")
            } catch (e: HttpStatusException) {
                if (e.status.value != 401) throw e
                refreshes++
                try {
                    call("Bearer fresh")
                } catch (e2: HttpStatusException) {
                    if (e2.status.value == 401) throw SignInRequiredException(server.name)
                    throw e2
                }
            }
    }

    private class Repo(private val session: PlaybackSession) : FakeBookRepository() {
        var starts = 0
        val closed = mutableListOf<Pair<String, Long>>()
        var startResult: () -> Result<PlaybackSession> = { Result.success(session) }
        override suspend fun startPlayback(bookId: String): Result<PlaybackSession> { starts++; return startResult() }
        override suspend fun closePlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long): Result<Unit> {
            closed += session.sessionId to positionMs
            return Result.success(Unit)
        }
    }

    /** Counts background tasks; [expire] plays the system running out of time. */
    class FakeBackground : BackgroundWork {
        var begun = 0
        var ended = 0
        private var onExpired: (() -> Unit)? = null
        override fun begin(onExpired: () -> Unit): () -> Unit {
            begun++
            this.onExpired = onExpired
            var done = false
            return { if (!done) { done = true; ended++ } }
        }
        fun expire() = onExpired?.invoke()
    }

    private class Rig(
        scope: TestScope,
        session: PlaybackSession,
        server: ServerConfig,
        answer: (url: String, auth: String?) -> Pair<HttpStatusCode, String?> = { _, _ -> HttpStatusCode.PartialContent to null },
        keepsBook: Boolean = false,
        canPlay: (PlaybackTrack) -> Boolean = { true },
    ) {
        val background = FakeBackground()
        var idles = 0
        val engine = FakeEngine()
        val repo = Repo(session)
        val authorizer = RefreshingAuthorizer()
        val requests = mutableListOf<Pair<String, String?>>()
        var now = 0L
        private val http = HttpClient(MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            requests += request.url.toString() to auth
            val (status, location) = answer(request.url.toString(), auth)
            respond("", status, if (location != null) headersOf(HttpHeaders.Location, location) else headersOf())
        }) {
            followRedirects = false
            expectSuccess = false
        }
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller = EnginePlaybackController(
            engine = engine,
            repository = repo,
            streams = StreamAuthorizer(FakeServerList(listOf(server)), authorizer, http),
            appScope = CoroutineScope(dispatcher),
            localFiles = NoLocalBookFiles,
            fileUri = { "file://$it" },
            clock = { now },
            main = dispatcher,
            keepsBookWhenIdle = keepsBook,
            onIdle = { idles++ },
            background = background,
            canPlay = canPlay,
        )
    }

    /** Runs [block] with a fresh rig and releases its controller before the test ends (its ticker would run forever). */
    private fun rigTest(
        answer: (url: String, auth: String?) -> Pair<HttpStatusCode, String?> = { _, _ -> HttpStatusCode.PartialContent to null },
        keepsBook: Boolean = false,
        session: PlaybackSession = this.session,
        canPlay: (PlaybackTrack) -> Boolean = { true },
        block: suspend TestScope.(Rig) -> Unit,
    ) = runTest {
        val r = Rig(this, session, server, answer, keepsBook, canPlay)
        try {
            block(r)
        } finally {
            r.controller.release()
        }
    }

    @Test
    fun `a book loads as one timeline over its files with the token in the headers`() = rigTest { r ->
        r.controller.load(book, 130_000L)
        assertEquals(listOf("$base/api/items/b1/file/1", "$base/api/items/b1/file/2"), r.engine.items.map { it.url })
        assertEquals(1, r.engine.currentIndex)
        assertEquals(30_000L, r.engine.currentOffsetMs)
        assertEquals(mapOf(HttpHeaders.Authorization to "Bearer tok"), r.engine.headers.values)
        // The start item's address was checked first, with the token, never in the address.
        assertEquals(listOf<Pair<String, String?>>("$base/api/items/b1/file/2" to "Bearer tok"), r.requests.toList())
        val s = r.controller.state.value
        assertEquals("b1", s.bookId)
        assertEquals(130_000L, s.positionMs)
        assertEquals(250_000L, s.durationMs)
        assertEquals(1, s.chapterIndex)
        assertEquals(session, r.controller.activeSession)
        assertEquals(BookLabel(book.title, book.author), r.controller.label.value)
        assertFalse(s.isPlaying)
    }

    @Test
    fun `skips cross files on the book's timeline and stay inside the book`() = rigTest { r ->
        r.controller.load(book, 90_000L)
        r.controller.skipForward()
        assertEquals(1 to 20_000L, r.engine.currentIndex to r.engine.currentOffsetMs)
        r.controller.skipBack()
        assertEquals(1 to 5_000L, r.engine.currentIndex to r.engine.currentOffsetMs)
        r.controller.seekTo(5_000L)
        r.controller.skipBack()
        assertEquals(0 to 0L, r.engine.currentIndex to r.engine.currentOffsetMs)
        r.controller.seekTo(240_000L)
        r.controller.skipForward()
        assertEquals(1 to 150_000L, r.engine.currentIndex to r.engine.currentOffsetMs)
        r.controller.seekToChapter(1)
        assertEquals(1 to 0L, r.engine.currentIndex to r.engine.currentOffsetMs)
        r.controller.seekToChapter(7)
        assertEquals(1 to 0L, r.engine.currentIndex to r.engine.currentOffsetMs)
    }

    @Test
    fun `play after a pause rewinds a little and a jump while paused does not`() = rigTest { r ->
        r.controller.load(book, 50_000L)
        r.controller.play()
        r.controller.pause()
        r.now += 60_000L // a minute: 5 s back
        r.controller.play()
        assertEquals(45_000L, r.engine.currentOffsetMs)
        r.controller.pause()
        r.now += 60_000L
        r.controller.seekTo(70_000L)
        r.controller.play()
        assertEquals(70_000L, r.engine.currentOffsetMs)
    }

    @Test
    fun `speed is kept across loads and given to the engine`() = rigTest { r ->
        r.controller.setSpeed(1.5f)
        r.controller.load(book, 0L)
        r.controller.play()
        assertEquals(1.5f, r.engine.speed)
        assertEquals(1.5f, r.controller.state.value.speed)
    }

    @Test
    fun `an interruption resumes by itself only when the system says so and without a rewind`() = rigTest { r ->
        r.controller.load(book, 50_000L)
        r.controller.play()
        r.controller.onInterruptionBegan()
        assertFalse(r.engine.playWhenReady)
        r.now += 120_000L
        r.controller.onInterruptionEnded(shouldResume = true)
        assertTrue(r.engine.playWhenReady)
        assertEquals(50_000L, r.engine.currentOffsetMs)

        r.controller.onInterruptionBegan()
        r.controller.onInterruptionEnded(shouldResume = false)
        assertFalse(r.engine.playWhenReady)
        // Pressing Play afterwards rewinds as after any pause.
        r.now += 120_000L
        r.controller.play()
        assertEquals(45_000L, r.engine.currentOffsetMs)
    }

    @Test
    fun `an interruption of a paused book never starts it`() = rigTest { r ->
        r.controller.load(book, 50_000L)
        r.controller.onInterruptionBegan()
        r.controller.onInterruptionEnded(shouldResume = true)
        assertFalse(r.engine.playWhenReady)
    }

    @Test
    fun `headphones unplugged pause the book`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.controller.onRouteChange(RouteChange.NEW_DEVICE_AVAILABLE)
        assertTrue(r.engine.playWhenReady)
        r.controller.onRouteChange(RouteChange.OLD_DEVICE_UNAVAILABLE)
        assertFalse(r.engine.playWhenReady)
    }

    @Test
    fun `lock screen commands drive the player and do nothing without a book`() = rigTest { r ->
        assertFalse(r.controller.onRemoteCommand(RemoteCommand.Play))
        r.controller.load(book, 10_000L)
        assertTrue(r.controller.onRemoteCommand(RemoteCommand.TogglePlayPause))
        assertTrue(r.engine.playWhenReady)
        assertTrue(r.controller.onRemoteCommand(RemoteCommand.TogglePlayPause))
        assertFalse(r.engine.playWhenReady)
        assertTrue(r.controller.onRemoteCommand(RemoteCommand.ChangePosition(120_000L)))
        assertEquals(1 to 20_000L, r.engine.currentIndex to r.engine.currentOffsetMs)
        assertTrue(r.controller.onRemoteCommand(RemoteCommand.PreviousChapter))
        assertEquals(1 to 0L, r.engine.currentIndex to r.engine.currentOffsetMs)
        assertFalse(r.controller.onRemoteCommand(RemoteCommand.NextChapter))
    }

    @Test
    fun `the position ticks twice a second while playing`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.engine.currentOffsetMs = 1_000L
        advanceTimeBy(600L)
        assertEquals(1_000L, r.controller.state.value.positionMs)
        assertTrue(r.controller.state.value.isPlaying)
    }

    @Test
    fun `the sleep timer pauses after the time played`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.controller.setSleepTimer(SleepTimer.After(1))
        repeat(130) {
            r.now += 500L
            advanceTimeBy(500L)
        }
        assertFalse(r.engine.playWhenReady)
        assertEquals(SleepTimer.Off, r.controller.sleepTimer.value)
    }

    @Test
    fun `ten minutes paused ends the session and forgets the book`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.engine.currentOffsetMs = 30_000L
        r.controller.pause()
        r.now += EnginePlaybackController.PAUSED_END_AFTER_MS + 1_000L
        advanceTimeBy(EnginePlaybackController.PAUSED_END_AFTER_MS + 1_000L)
        runCurrent()
        assertNull(r.controller.state.value.bookId)
        assertNull(r.controller.activeSession)
        assertEquals(listOf("sess" to 30_000L), r.repo.closed)
        assertEquals(1, r.idles)
    }

    @Test
    fun `on iOS ten minutes paused closes only the session - the book and the card stay and Play opens a new one`() =
        rigTest(keepsBook = true) { r ->
            r.controller.load(book, 0L)
            r.controller.play()
            r.engine.currentOffsetMs = 30_000L
            r.controller.pause()
            r.now += EnginePlaybackController.PAUSED_END_AFTER_MS + 1_000L
            advanceTimeBy(EnginePlaybackController.PAUSED_END_AFTER_MS + 1_000L)
            runCurrent()
            assertEquals(listOf("sess" to 30_000L), r.repo.closed)
            assertEquals("b1", r.controller.state.value.bookId)
            assertEquals(30_000L, r.controller.state.value.positionMs)
            assertEquals(BookLabel(book.title, book.author), r.controller.label.value)
            assertNull(r.controller.activeSession)
            assertEquals(1, r.idles)
            // The lock screen's Play: a new session, the book loaded again at its place (10 s back after the long pause).
            assertTrue(r.controller.onRemoteCommand(RemoteCommand.Play))
            awaitReal { r.repo.starts == 2 && r.engine.playWhenReady }
            assertEquals(2, r.repo.starts)
            assertEquals(20_000L, r.engine.currentOffsetMs)
            assertTrue(r.engine.playWhenReady)
            assertEquals(session, r.controller.activeSession)
            // The session closed by the idle rule is not closed a second time.
            assertEquals(listOf("sess" to 30_000L), r.repo.closed)
        }

    @Test
    fun `an idle timer that fires late after a Play changes nothing`() = rigTest(keepsBook = true) { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.controller.pause()
        // The phone slept: by the steady clock the pause is not long, and the listener pressed Play first anyway.
        advanceTimeBy(EnginePlaybackController.PAUSED_END_AFTER_MS - 1_000L)
        r.controller.play()
        r.now += EnginePlaybackController.PAUSED_END_AFTER_MS + 1_000L
        advanceTimeBy(EnginePlaybackController.PAUSED_END_AFTER_MS + 1_000L)
        runCurrent()
        assertTrue(r.repo.closed.isEmpty())
        assertEquals(session, r.controller.activeSession)
        assertTrue(r.engine.playWhenReady)
    }

    @Test
    fun `a start that fails closes its session at the session's own start - never at 0`() =
        rigTest(answer = { _, _ -> HttpStatusCode.NotFound to null }, session = session.copy(startPositionMs = 33_000L)) { r ->
            assertFailsWith<PlaybackStartException> { r.controller.load(book, 120_000L) }
            runCurrent()
            assertEquals(listOf("sess" to 33_000L), r.repo.closed)
        }

    @Test
    fun `a book in a format the phone cannot play fails clearly and leaves the server's progress alone`() =
        rigTest(session = session.copy(startPositionMs = 5_000L), canPlay = ::applePlays) { r ->
            r.repo.startResult = {
                Result.success(session.copy(startPositionMs = 5_000L, tracks = session.tracks.map { it.copy(mimeType = "audio/ogg") }))
            }
            val e = assertFailsWith<PlaybackStartException> { r.controller.load(book, 0L) }
            assertEquals(UNSUPPORTED_AUDIO_TEXT, e.message)
            runCurrent()
            assertEquals(listOf("sess" to 5_000L), r.repo.closed)
            assertTrue(r.requests.isEmpty())
        }

    @Test
    fun `loading another book closes the first session`() = rigTest { r ->
        r.controller.load(book, 20_000L)
        r.controller.load(book, 0L)
        runCurrent()
        assertEquals(listOf("sess" to 20_000L), r.repo.closed)
    }

    @Test
    fun `a refused token is refreshed once before the start`() = rigTest({ _, auth -> (if (auth == "Bearer tok") HttpStatusCode.Unauthorized else HttpStatusCode.PartialContent) to null }) { r ->
        r.controller.load(book, 0L)
        assertEquals(1, r.authorizer.refreshes)
        assertEquals(mapOf(HttpHeaders.Authorization to "Bearer fresh"), r.engine.headers.values)
    }

    @Test
    fun `a token refused twice fails the start with sign in again and closes the session`() = rigTest({ _, _ -> HttpStatusCode.Unauthorized to null }) { r ->
        val e = assertFailsWith<PlaybackStartException> { r.controller.load(book, 0L) }
        assertEquals(SIGN_IN_AGAIN_TEXT, e.message)
        runCurrent()
        assertEquals(listOf("sess" to 0L), r.repo.closed)
        assertNull(r.controller.state.value.bookId)
        assertTrue(r.engine.items.isEmpty())
    }

    @Test
    fun `audio redirected to another host never gets the token`() = rigTest({ url, _ ->
            if (url.startsWith(base)) HttpStatusCode.Found to "https://cdn.example.test/x" else HttpStatusCode.OK to null
        }) { r ->
        val e = assertFailsWith<PlaybackStartException> { r.controller.load(book, 0L) }
        assertEquals("The audio moved to another address", e.message)
        assertTrue(r.requests.none { it.first.startsWith("https://cdn.") })
    }

    @Test
    fun `a same-server redirect is fine`() = rigTest({ url, _ -> if (url.endsWith("/file/1")) HttpStatusCode.Found to "/real/1" else HttpStatusCode.PartialContent to null }) { r ->
        r.controller.load(book, 0L)
        assertEquals(listOf("$base/api/items/b1/file/1", "$base/real/1"), r.requests.map { it.first })
    }

    @Test
    fun `a recovery after a failure and a seek rebuilds where the engine stands now`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.now += 1_000L
        r.controller.onItemFailed(0, EngineFailure.NETWORK)
        awaitReal { r.engine.reloads == 1 }
        r.now += 1_000L
        r.controller.onItemFailed(0, EngineFailure.NETWORK)
        awaitReal { r.controller.state.value.error != null }
        r.controller.seekTo(130_000L)
        r.controller.play()
        awaitReal { r.engine.reloads == 2 }
        assertEquals(1 to 30_000L, r.engine.currentIndex to r.engine.currentOffsetMs)
    }

    @Test
    fun `a recovery runs inside a background task that ends with it`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.controller.onItemFailed(0, EngineFailure.NETWORK)
        awaitReal { r.engine.reloads == 1 }
        assertEquals(1, r.background.begun)
        awaitReal { r.background.ended == 1 }
        assertEquals(1, r.background.ended)
    }

    @Test
    fun `when the background time runs out the recovery stops cleanly and Play tries again`() =
        rigTest(answer = { _, _ -> Thread.sleep(300); HttpStatusCode.PartialContent to null }) { r ->
            r.controller.load(book, 0L)
            r.controller.play()
            r.controller.onItemFailed(0, EngineFailure.NETWORK)
            r.background.expire()
            assertEquals(RECOVERY_CUT_SHORT_TEXT, r.controller.state.value.error)
            assertFalse(r.engine.playWhenReady)
            r.controller.play()
            awaitReal { r.engine.reloads == 1 && r.engine.playWhenReady }
            assertTrue(r.engine.playWhenReady)
        }

    @Test
    fun `while playing the token is renewed and the next file gets the new headers`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.authorizer.current = "renewed"
        r.now += EnginePlaybackController.HEADER_REFRESH_MS
        advanceTimeBy(600L)
        awaitReal { r.engine.updatedHeaders.isNotEmpty() }
        assertEquals(listOf(mapOf(HttpHeaders.Authorization to "Bearer renewed")), r.engine.updatedHeaders.map { it.values })
    }

    @Test
    fun `the phone refusing the audio shows why and does not buffer for ever`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.engine.playWhenReady = false
        r.controller.onItemFailed(0, EngineFailure.AUDIO_SESSION)
        assertEquals(AUDIO_BUSY_TEXT, r.controller.state.value.error)
        assertFalse(r.controller.state.value.isBuffering)
        r.controller.play()
        assertTrue(r.engine.playWhenReady)
        assertEquals(0, r.engine.reloads)
    }

    @Test
    fun `after a media services reset the book is put back where it was and plays on`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.engine.currentIndex = 1
        r.engine.currentOffsetMs = 7_000L
        r.controller.onMediaServicesReset()
        assertEquals(2, r.engine.queues)
        assertEquals(1 to 7_000L, r.engine.currentIndex to r.engine.currentOffsetMs)
        assertTrue(r.engine.playWhenReady)
    }

    @Test
    fun `a failed item is checked again and rebuilt at its place once - a second failure soon after is shown`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.engine.currentOffsetMs = 42_000L
        r.controller.onItemFailed(0, EngineFailure.NETWORK)
        awaitReal { r.engine.reloads == 1 }
        assertEquals(1, r.engine.reloads)
        assertEquals(42_000L, r.engine.currentOffsetMs)
        assertTrue(r.engine.playWhenReady)
        assertNull(r.controller.state.value.error)

        r.now += 10_000L
        r.controller.onItemFailed(0, EngineFailure.NETWORK)
        runCurrent()
        assertEquals(1, r.engine.reloads)
        assertEquals("Cannot reach the server", r.controller.state.value.error)
        assertFalse(r.engine.playWhenReady)

        // Play tries again.
        r.controller.play()
        awaitReal { r.engine.reloads == 2 }
        assertEquals(2, r.engine.reloads)
        assertTrue(r.engine.playWhenReady)
        assertNull(r.controller.state.value.error)
    }

    private var refuse = false

    /** The stream check runs on the mock engine's own threads: wait (real time, at most 5 s) for [done]. */
    private fun TestScope.awaitReal(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < until) {
            runCurrent()
            if (done()) return
            Thread.sleep(5)
        }
    }

    @Test
    fun `a failed item whose server now refuses the token says sign in again`() = rigTest({ _, _ -> (if (refuse) HttpStatusCode.Unauthorized else HttpStatusCode.PartialContent) to null }) { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        refuse = true
        r.controller.onItemFailed(0, EngineFailure.OTHER)
        awaitReal { r.controller.state.value.error != null }
        assertEquals(SIGN_IN_AGAIN_TEXT, r.controller.state.value.error)
        assertEquals(0, r.engine.reloads)
    }

    @Test
    fun `a downloaded book plays from its files with no headers and no check`() = rigTest { r ->
        val files = listOf(
            LocalFile(ino = "1", path = "/d/1.mp3", durationMs = 100_000L, mimeType = "audio/mpeg"),
            LocalFile(ino = "2", path = "/d/2.mp3", durationMs = 150_000L, mimeType = "audio/mpeg"),
        )
        r.controller.load(book, 0L, AudioOrigin.Phone(files, openSession = false))
        assertEquals(listOf("file:///d/1.mp3", "file:///d/2.mp3"), r.engine.items.map { it.url })
        assertTrue(r.engine.headers.isEmpty)
        assertTrue(r.requests.isEmpty())
        assertEquals(0, r.repo.starts)
        assertNull(r.controller.activeSession)
        // A file that fails is not retried.
        r.controller.onItemFailed(0, EngineFailure.OTHER)
        assertEquals("The downloaded file could not be played", r.controller.state.value.error)
        // Removing the download stops it.
        r.controller.releaseLocalFiles("b1")
        runCurrent()
        assertNull(r.controller.state.value.bookId)
    }

    @Test
    fun `the end of the book is not a pause to rewind from`() = rigTest { r ->
        r.controller.load(book, 0L)
        r.controller.play()
        r.engine.currentIndex = 1
        r.engine.currentOffsetMs = 150_000L
        r.engine.ended = true
        r.engine.playWhenReady = false
        r.controller.onEnded()
        assertEquals(250_000L, r.controller.state.value.positionMs)
        assertFalse(r.controller.state.value.isPlaying)
    }
}
