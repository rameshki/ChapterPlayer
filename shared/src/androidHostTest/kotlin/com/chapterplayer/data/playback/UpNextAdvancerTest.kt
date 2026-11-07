package com.chapterplayer.data.playback

import com.chapterplayer.data.downloads.LocalFile
import com.chapterplayer.data.merge.FixedSourceMerging
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.upnext.InMemoryUpNext
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import com.chapterplayer.data.upnext.UpNextRepository
import com.chapterplayer.domain.playback.SleepTimer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpNextAdvancerTest {

    private val a = testBook("a").copy(durationMs = 60_000L)
    private val b = testBook("b").copy(durationMs = 60_000L)
    private val c = testBook("c").copy(durationMs = 60_000L)
    private val controller = FakePlaybackController()
    private val repo = FakeBookRepository(listOf(a, b, c))
    private val settings = FakeAppSettings()
    private val connectivity = FakeConnectivity()
    private val local = FakeLocalBookFiles()
    private val downloads = FakeDownloadStates()
    private val queue = InMemoryUpNext()
    private val notices = UpNextNotices()

    private var clock = 0L
    private val background = object : BackgroundWork {
        var begun = 0
        var ended = 0
        override fun begin(onExpired: () -> Unit): () -> Unit {
            begun++
            return { ended++ }
        }
    }

    private fun TestScope.advancer(merging: FixedSourceMerging? = null, upNext: UpNextRepository = queue): UpNextAdvancer {
        val main = UnconfinedTestDispatcher(testScheduler)
        val starter = PlaybackStarter(controller, repo, settings, connectivity, local)
        return UpNextAdvancer(
            controller, upNext, repo, downloads, connectivity, settings, starter, backgroundScope, notices,
            merging = merging ?: FixedSourceMerging(MergeContext(onlineServerIds = setOf("s1"))), main = main,
            background = background, nowMs = { clock },
        ).also { it.start() }
    }

    /** The queue, but its first read waits for [gate]: the advance is held there while the test acts. */
    private class GatedUpNext(val inner: InMemoryUpNext, val gate: CompletableDeferred<Unit>) : UpNextRepository {
        override val queue: Flow<List<String>> = flow {
            gate.await()
            emitAll(inner.ids)
        }
        override suspend fun edit(change: (List<String>) -> List<String>) = inner.edit(change)
    }

    private fun TestScope.collectNotices(): List<String> {
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.messages.toList(seen) }
        return seen
    }

    /** Book [id] plays and then reaches its end while playing. */
    private fun playsToTheEnd(id: String = "a") {
        controller.playback.value = PlaybackState(bookId = id, isPlaying = true, playWhenReady = true, positionMs = 59_000L, durationMs = 60_000L)
        controller.playback.value = PlaybackState(bookId = id, playWhenReady = true, positionMs = 60_000L, durationMs = 60_000L, ended = true)
    }

    @Test
    fun `the first queued book starts when the book ends and leaves the queue`() = runTest {
        queue.ids.value = listOf("b", "c")
        advancer()

        playsToTheEnd()

        assertEquals(listOf("b"), controller.loads.map { it.first })
        assertEquals(1, controller.plays)
        assertEquals(listOf("c"), queue.ids.value)
    }

    @Test
    fun `an empty queue starts nothing`() = runTest {
        advancer()
        playsToTheEnd()
        assertTrue(controller.loads.isEmpty())
    }

    @Test
    fun `a pause just before the end starts nothing`() = runTest {
        queue.ids.value = listOf("b")
        advancer()

        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, playWhenReady = true, positionMs = 58_000L, durationMs = 60_000L)
        controller.playback.value = PlaybackState(bookId = "a", playWhenReady = false, positionMs = 59_000L, durationMs = 60_000L)
        // A seek to the end while paused.
        controller.playback.value = PlaybackState(bookId = "a", playWhenReady = false, positionMs = 60_000L, durationMs = 60_000L, ended = true)

        assertTrue(controller.loads.isEmpty())
        assertEquals(listOf("b"), queue.ids.value)
    }

    @Test
    fun `a sleep timer that runs out at the end starts nothing`() = runTest {
        queue.ids.value = listOf("b")
        advancer()

        controller.playback.value = PlaybackState(
            bookId = "a", isPlaying = true, playWhenReady = true, positionMs = 59_000L, durationMs = 60_000L, sleepRemainingMs = 1_000L,
        )
        controller.playback.value = PlaybackState(bookId = "a", playWhenReady = true, positionMs = 60_000L, durationMs = 60_000L, ended = true)

        assertTrue(controller.loads.isEmpty())
    }

    @Test
    fun `switching books starts nothing from the queue`() = runTest {
        queue.ids.value = listOf("b")
        advancer()

        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, playWhenReady = true, positionMs = 10_000L, durationMs = 60_000L)
        controller.playback.value = PlaybackState(bookId = "c", isPlaying = true, playWhenReady = true, positionMs = 0L, durationMs = 60_000L)

        assertTrue(controller.loads.isEmpty())
        assertEquals(listOf("b"), queue.ids.value)
    }

    @Test
    fun `a queued book started by hand leaves the queue`() = runTest {
        queue.ids.value = listOf("b", "c")
        advancer()

        controller.playback.value = PlaybackState(bookId = "c", isPlaying = true, playWhenReady = true, durationMs = 60_000L)

        assertEquals(listOf("b"), queue.ids.value)
    }

    @Test
    fun `a queued book that is only loaded stays queued until it plays`() = runTest {
        queue.ids.value = listOf("c")
        advancer()

        controller.playback.value = PlaybackState(bookId = "c", isBuffering = true, playWhenReady = true, durationMs = 60_000L)
        assertEquals(listOf("c"), queue.ids.value)
        controller.playback.value = PlaybackState(bookId = "c", isPlaying = true, playWhenReady = true, durationMs = 60_000L)
        assertEquals(emptyList(), queue.ids.value)
    }

    @Test
    fun `offline a book that is not downloaded is skipped with a note and the next downloaded one starts`() = runTest {
        val seen = collectNotices()
        connectivity.online.value = false
        downloads.states.value = mapOf("c" to DownloadState.DOWNLOADED)
        local.files["c"] = listOf(LocalFile("1", "/d/c/1.m4b", 60_000L, "audio/mp4"))
        queue.ids.value = listOf("b", "c")
        advancer(FixedSourceMerging(MergeContext(onlineServerIds = emptySet(), deviceOnline = false)))

        playsToTheEnd()

        assertEquals(listOf("c"), controller.loads.map { it.first })
        // The skipped book stays queued for later.
        assertEquals(listOf("b"), queue.ids.value)
        assertEquals(listOf("Up next skipped \"Title b\": needs a connection"), seen)
    }

    @Test
    fun `offline with nothing downloaded nothing starts and the queue stays`() = runTest {
        val seen = collectNotices()
        connectivity.online.value = false
        queue.ids.value = listOf("b", "c")
        advancer(FixedSourceMerging(MergeContext(onlineServerIds = emptySet(), deviceOnline = false)))

        playsToTheEnd()

        assertTrue(controller.loads.isEmpty())
        assertEquals(listOf("b", "c"), queue.ids.value)
        assertEquals(1, seen.size)
    }

    @Test
    fun `downloaded only skips a book that would stream`() = runTest {
        settings.downloadedOnlyFlow.value = true
        queue.ids.value = listOf("b")
        advancer()

        playsToTheEnd()

        assertTrue(controller.loads.isEmpty())
        assertEquals(listOf("b"), queue.ids.value)
    }

    @Test
    fun `a start that fails keeps the entry and says why once`() = runTest {
        val seen = collectNotices()
        controller.onLoad = { _, _ -> throw PlaybackStartException("The server did not answer") }
        queue.ids.value = listOf("b", "c")
        advancer()

        playsToTheEnd()

        assertEquals(listOf("b"), controller.loads.map { it.first })
        assertEquals(listOf("b", "c"), queue.ids.value)
        assertEquals(listOf("Up next could not start the next book. The server did not answer"), seen)
        // The same ended state again does not try again.
        controller.playback.value = controller.playback.value.copy(positionMs = 60_000L)
        assertEquals(1, controller.loads.size)
    }

    @Test
    fun `a queued book that is gone is dropped`() = runTest {
        queue.ids.value = listOf("gone", "b")
        advancer()

        playsToTheEnd()

        assertEquals(listOf("b"), controller.loads.map { it.first })
        assertEquals(emptyList(), queue.ids.value)
    }

    @Test
    fun `a merged book plays its downloaded copy`() = runTest {
        val plex = b.copy(id = "plex_b", serverId = "p1", source = ServerSource.PLEX)
        repo.books.value = listOf(a, b, plex)
        downloads.states.value = mapOf("plex_b" to DownloadState.DOWNLOADED)
        local.files["plex_b"] = listOf(LocalFile("9", "/d/plex_b/9.mp3", 60_000L, "audio/mpeg"))
        queue.ids.value = listOf("b")
        advancer(FixedSourceMerging(MergeContext(onlineServerIds = setOf("s1", "p1"))))

        playsToTheEnd()

        assertEquals(listOf("plex_b"), controller.loads.map { it.first })
        assertEquals(emptyList(), queue.ids.value)
    }

    @Test
    fun `a sleep timer keeps its time left on the next book`() = runTest {
        queue.ids.value = listOf("b")
        controller.sleep.value = SleepTimer.After(60)
        advancer()

        controller.playback.value = PlaybackState(
            bookId = "a", isPlaying = true, playWhenReady = true, positionMs = 59_000L, durationMs = 60_000L, sleepRemainingMs = 20 * 60_000L,
        )
        controller.playback.value = PlaybackState(bookId = "a", playWhenReady = true, positionMs = 60_000L, durationMs = 60_000L, ended = true)

        assertEquals(listOf("b"), controller.loads.map { it.first })
        // 20 min on the timer, 1 s of book still to play: the rest carries over.
        assertEquals(listOf<SleepTimer>(SleepTimer.After(20, 20 * 60_000L - 1_000L)), controller.sleepSets)
    }

    @Test
    fun `without a sleep timer none is set on the next book`() = runTest {
        queue.ids.value = listOf("b")
        advancer()
        playsToTheEnd()
        assertEquals(listOf("b"), controller.loads.map { it.first })
        assertTrue(controller.sleepSets.isEmpty())
    }

    @Test
    fun `a book picked during the advance wins and the advance starts nothing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        queue.ids.value = listOf("b", "c")
        advancer(upNext = GatedUpNext(queue, gate))

        playsToTheEnd()
        // While the advance waits, the person starts c (the car, a widget, the app).
        controller.playback.value = PlaybackState(bookId = "c", isPlaying = true, playWhenReady = true, durationMs = 60_000L)
        gate.complete(Unit)

        assertTrue(controller.loads.isEmpty())
        // c left the queue because it played; b stays for the next end.
        assertEquals(listOf("b"), queue.ids.value)
    }

    @Test
    fun `playing the ended book again cancels the advance`() = runTest {
        val gate = CompletableDeferred<Unit>()
        queue.ids.value = listOf("b")
        advancer(upNext = GatedUpNext(queue, gate))

        playsToTheEnd()
        // A seek back into the ended book.
        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, playWhenReady = true, positionMs = 30_000L, durationMs = 60_000L)
        gate.complete(Unit)

        assertTrue(controller.loads.isEmpty())
        assertEquals(listOf("b"), queue.ids.value)
    }

    @Test
    fun `the advance runs inside background work`() = runTest {
        queue.ids.value = listOf("b")
        advancer()
        playsToTheEnd()
        assertEquals(1, background.begun)
        assertEquals(1, background.ended)
    }

    @Test
    fun `a book played again after the player let it go leaves the queue again`() = runTest {
        advancer()
        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, playWhenReady = true, durationMs = 60_000L)
        // The player let the book go (a long pause ended the session); then the person queued it and resumed it.
        controller.playback.value = PlaybackState()
        queue.ids.value = listOf("a", "b")
        controller.playback.value = PlaybackState(bookId = "a", isPlaying = true, playWhenReady = true, durationMs = 60_000L)
        assertEquals(listOf("b"), queue.ids.value)
    }

    @Test
    fun `downloaded only names its reason`() = runTest {
        val seen = collectNotices()
        settings.downloadedOnlyFlow.value = true
        queue.ids.value = listOf("b")
        advancer()
        playsToTheEnd()
        assertEquals(listOf("Up next: \"Title b\" cannot play now (Downloaded only is on)"), seen)
    }

    @Test
    fun `each end moves on once`() = runTest {
        queue.ids.value = listOf("b", "c")
        advancer()

        playsToTheEnd("a")
        // b loaded by the advance (the fake player buffers), then it plays to its end too.
        playsToTheEnd("b")

        assertEquals(listOf("b", "c"), controller.loads.map { it.first })
        assertEquals(emptyList(), queue.ids.value)
    }
}
