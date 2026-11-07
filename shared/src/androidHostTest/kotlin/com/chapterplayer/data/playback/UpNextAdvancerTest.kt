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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun TestScope.advancer(merging: FixedSourceMerging? = null): UpNextAdvancer {
        val main = UnconfinedTestDispatcher(testScheduler)
        val starter = PlaybackStarter(controller, repo, settings, connectivity, local)
        return UpNextAdvancer(
            controller, queue, repo, downloads, connectivity, settings, starter, backgroundScope, notices,
            merging = merging ?: FixedSourceMerging(MergeContext(onlineServerIds = setOf("s1"))), main = main,
        ).also { it.start() }
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
        assertEquals(listOf("Up next skipped \"Title b\": not playable now"), seen)
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
