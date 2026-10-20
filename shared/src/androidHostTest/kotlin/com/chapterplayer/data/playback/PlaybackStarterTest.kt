package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackStarterTest {

    private val book = testBook("a").copy(durationMs = 100_000L)
    private val controller = FakePlaybackController()
    private val repo = FakeBookRepository(listOf(book))
    private val settings = FakeAppSettings()
    private val starter = PlaybackStarter(controller, repo, settings)

    @Test
    fun `an explicit position is used as given, even when progress exists`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L))

        assertTrue(starter.start(book, 7_000L).isSuccess)

        assertEquals(listOf("a" to 7_000L), controller.loads)
    }

    @Test
    fun `an explicit position of zero is not replaced by progress`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L))
        starter.start(book, 0L)
        assertEquals(listOf("a" to 0L), controller.loads)
    }

    @Test
    fun `without a position it starts where local progress left off`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L))
        starter.start(book)
        assertEquals(listOf("a" to 40_000L), controller.loads)
    }

    @Test
    fun `with no local progress the server position is asked for`() = runTest {
        starter.start(book)
        assertEquals(listOf("a" to -1L), controller.loads)
    }

    @Test
    fun `a book finished here starts over`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 100_000L, 100_000L, 1L, isFinished = true))
        starter.start(book)
        assertEquals(listOf("a" to 0L), controller.loads)
    }

    @Test
    fun `a position at the end counts as finished even without the flag`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 100_000L, 100_000L, 1L))
        starter.start(book)
        assertEquals(listOf("a" to 0L), controller.loads)
    }

    @Test
    fun `the saved speed is applied after load and before play`() = runTest {
        settings.speed.value = 1.5f

        starter.start(book, 0L)

        assertEquals(listOf(1.5f), controller.speeds)
        assertEquals(listOf("load", "speed", "play"), controller.calls)
    }

    @Test
    fun `a start failure keeps the safe message of a PlaybackStartException`() = runTest {
        controller.onLoad = { _, _ -> throw PlaybackStartException("Sign in again in Settings") }

        val result = starter.start(book, 0L)

        assertEquals("Sign in again in Settings", result.exceptionOrNull()?.message)
        assertEquals(0, controller.plays)
        assertTrue(controller.speeds.isEmpty())
    }

    @Test
    fun `any other failure becomes the generic text without the raw message`() = runTest {
        controller.onLoad = { _, _ -> error("secret http://host.test/?token=abc") }

        val result = starter.start(book, 0L)

        assertEquals("Could not start playback", result.exceptionOrNull()?.message)
        assertIs<PlaybackStartException>(result.exceptionOrNull())
        assertEquals(0, controller.plays)
    }

    @Test
    fun `cancellation is rethrown, not wrapped`() = runTest {
        controller.onLoad = { _, _ -> throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { starter.start(book, 0L) }
        assertEquals(0, controller.plays)
    }

    @Test
    fun `an unreadable saved speed does not stop the book playing`() = runTest {
        val failing = object : com.chapterplayer.data.settings.AppSettings by settings {
            override val playbackSpeed: kotlinx.coroutines.flow.Flow<Float> = kotlinx.coroutines.flow.flow { error("storage unavailable") }
        }

        val result = PlaybackStarter(controller, repo, failing).start(book, 0L)

        assertTrue(result.isSuccess)
        assertEquals(listOf(1.0f), controller.speeds)
        assertEquals(1, controller.plays)
    }

    @Test
    fun `a speed the player refuses does not turn a loaded book into an error`() = runTest {
        val refusing = object : PlaybackController by controller {
            override fun setSpeed(speed: Float) { error("player busy") }
        }

        val result = PlaybackStarter(refusing, repo, settings).start(book, 0L)

        assertTrue(result.isSuccess)
        assertEquals(1, controller.plays)
    }

    @Test
    fun `cancellation while reading the saved speed is rethrown`() = runTest {
        val cancelling = object : com.chapterplayer.data.settings.AppSettings by settings {
            override val playbackSpeed: kotlinx.coroutines.flow.Flow<Float> = kotlinx.coroutines.flow.flow { throw CancellationException("stop") }
        }

        assertFailsWith<CancellationException> { PlaybackStarter(controller, repo, cancelling).start(book, 0L) }
        assertEquals(0, controller.plays)
    }

    @Test
    fun `the start without a position is the shared rule`() = runTest {
        for (progress in listOf(null, ReadingProgress("a", 0L, 100_000L, 1L), ReadingProgress("a", 40_000L, 100_000L, 1L),
            ReadingProgress("a", 50L, 100_000L, 1L, isFinished = true), ReadingProgress("a", 100_000L, 100_000L, 1L))) {
            repo.progress.value = listOfNotNull(progress)
            controller.loads.clear()
            controller.playback.value = com.chapterplayer.data.playback.PlaybackState()
            starter.start(book)
            assertEquals(com.chapterplayer.domain.playback.startPositionFor(progress, book.durationMs), controller.loads.single().second)
        }
    }

    @Test
    fun `a paused loaded book is played on without a reload`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 10_000L, 100_000L, 1L))
        controller.playback.value = com.chapterplayer.data.playback.PlaybackState(bookId = "a", positionMs = 50_000L)

        assertTrue(starter.start(book, null).isSuccess)

        assertTrue(controller.loads.isEmpty())
        assertEquals(1, controller.plays)
    }

    @Test
    fun `a playing loaded book is left alone`() = runTest {
        controller.playback.value = com.chapterplayer.data.playback.PlaybackState(bookId = "a", isPlaying = true)

        assertTrue(starter.start(book, null).isSuccess)

        assertTrue(controller.loads.isEmpty())
        assertEquals(0, controller.plays)
    }

    @Test
    fun `a book buffering towards its first sound is left alone`() = runTest {
        controller.playback.value = com.chapterplayer.data.playback.PlaybackState(bookId = "a", isBuffering = true, playWhenReady = true)

        assertTrue(starter.start(book, null).isSuccess)

        assertEquals(0, controller.plays)
        assertTrue(controller.loads.isEmpty())
    }

    @Test
    fun `another book loaded means a normal load`() = runTest {
        controller.playback.value = com.chapterplayer.data.playback.PlaybackState(bookId = "other", isPlaying = true)

        assertTrue(starter.start(book, null).isSuccess)

        assertEquals(listOf("a"), controller.loads.map { it.first })
    }

    @Test
    fun `an explicit position still loads a book that is already loaded`() = runTest {
        controller.playback.value = com.chapterplayer.data.playback.PlaybackState(bookId = "a")

        assertTrue(starter.start(book, 5_000L).isSuccess)

        assertEquals(listOf("a" to 5_000L), controller.loads)
    }
}
