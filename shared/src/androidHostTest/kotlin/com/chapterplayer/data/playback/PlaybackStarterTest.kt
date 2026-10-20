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
}
