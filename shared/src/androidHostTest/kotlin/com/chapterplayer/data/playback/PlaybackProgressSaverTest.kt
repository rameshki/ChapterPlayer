package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressSaverTest {

    private class Rig(scope: CoroutineScope) {
        val controller = FakePlaybackController()
        val repo = FakeBookRepository(listOf(testBook("a")))
        var now = 777L
        val saver = PlaybackProgressSaver(controller, repo, scope, nowMs = { now }).also { it.start() }
    }

    private fun state(book: String? = "a", playing: Boolean, pos: Long, duration: Long = 100_000L) =
        PlaybackState(bookId = book, isPlaying = playing, positionMs = pos, durationMs = duration)

    private fun kotlinx.coroutines.test.TestScope.rig() =
        Rig(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `progress is saved when playing stops`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 30_000)
        r.controller.playback.value = state(playing = false, pos = 31_000)
        assertEquals(listOf(ReadingProgress("a", 31_000L, 100_000L, 777L, isFinished = false)), r.repo.saved)
    }

    @Test
    fun `progress within five seconds of the end is saved as finished`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 90_000)
        r.controller.playback.value = state(playing = false, pos = 95_000)
        assertTrue(r.repo.saved.single().isFinished)
    }

    @Test
    fun `nothing is saved while playing, while paused or for a book that never played`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = false, pos = 5_000)
        r.controller.playback.value = state(playing = true, pos = 5_000)
        r.controller.playback.value = state(playing = true, pos = 6_000)
        assertTrue(r.repo.saved.isEmpty())
        r.controller.playback.value = state(playing = false, pos = 6_000)
        r.controller.playback.value = state(playing = false, pos = 7_000)
        assertEquals(1, r.repo.saved.size)
    }

    @Test
    fun `no book or position zero is not saved`() = runTest {
        val r = rig()
        r.controller.playback.value = state(book = null, playing = true, pos = 5_000)
        r.controller.playback.value = state(book = null, playing = false, pos = 5_000)
        r.controller.playback.value = state(playing = true, pos = 0)
        r.controller.playback.value = state(playing = false, pos = 0)
        assertTrue(r.repo.saved.isEmpty())
    }

    @Test
    fun `the old book is saved when another book takes over, with its own last position`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 20_000)
        r.controller.playback.value = state(book = "other", playing = false, pos = 0)
        assertEquals("a", r.repo.saved.single().bookId)
        assertEquals(20_000L, r.repo.saved.single().positionMs)
    }

    @Test
    fun `a book taking over while the old one plays is saved too, not the new one`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 20_000)
        r.controller.playback.value = state(book = "other", playing = true, pos = 10)
        assertEquals(listOf("a"), r.repo.saved.map { it.bookId })
    }

    @Test
    fun `release while playing saves the book`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 42_000)
        r.controller.playback.value = PlaybackState()
        assertEquals(42_000L, r.repo.saved.single().positionMs)
    }

    @Test
    fun `starting twice observes once`() = runTest {
        val r = rig()
        r.saver.start()
        r.controller.playback.value = state(playing = true, pos = 1_000)
        r.controller.playback.value = state(playing = false, pos = 2_000)
        assertEquals(1, r.repo.saved.size)
    }

    // -- Periodic saves, on virtual time --------------------------------------------------------

    private class Timed(scope: CoroutineScope, now: () -> Long) {
        val controller = FakePlaybackController()
        val repo = FakeBookRepository(listOf(testBook("a")))
        init { PlaybackProgressSaver(controller, repo, scope, nowMs = now).start() }
    }

    private fun kotlinx.coroutines.test.TestScope.timed() = Timed(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        { testScheduler.currentTime }
    )

    @Test
    fun `while playing the position is saved every ten seconds`() = runTest {
        val t = timed()
        t.controller.playback.value = state(playing = true, pos = 1_000)
        advanceTimeBy(9_500)
        assertTrue(t.repo.saved.isEmpty())
        t.controller.playback.value = state(playing = true, pos = 10_000)
        advanceTimeBy(1_000)
        assertEquals(listOf(10_000L), t.repo.saved.map { it.positionMs })
        t.controller.playback.value = state(playing = true, pos = 20_000)
        advanceTimeBy(10_000)
        assertEquals(listOf(10_000L, 20_000L), t.repo.saved.map { it.positionMs })
        assertEquals(10_000L, t.repo.saved.first().lastPlayedAt)
    }

    @Test
    fun `nothing is saved periodically while paused`() = runTest {
        val t = timed()
        t.controller.playback.value = state(playing = false, pos = 5_000)
        advanceTimeBy(60_000)
        assertTrue(t.repo.saved.isEmpty())
    }

    @Test
    fun `a periodic save does not store position zero or a missing book`() = runTest {
        val t = timed()
        t.controller.playback.value = state(playing = true, pos = 0)
        advanceTimeBy(30_000)
        t.controller.playback.value = state(book = null, playing = true, pos = 5_000)
        advanceTimeBy(30_000)
        assertTrue(t.repo.saved.isEmpty())
    }

    @Test
    fun `periodic saves stop after a pause and the pause saves once`() = runTest {
        val t = timed()
        t.controller.playback.value = state(playing = true, pos = 5_000)
        advanceTimeBy(10_500)
        t.controller.playback.value = state(playing = false, pos = 12_000)
        assertEquals(listOf(5_000L, 12_000L), t.repo.saved.map { it.positionMs })
        advanceTimeBy(60_000)
        assertEquals(2, t.repo.saved.size)
    }

    @Test
    fun `a switch and a release still save the old book while the timer runs`() = runTest {
        val t = timed()
        t.controller.playback.value = state(playing = true, pos = 20_000)
        advanceTimeBy(3_000)
        t.controller.playback.value = state(book = "other", playing = true, pos = 10)
        assertEquals(listOf("a" to 20_000L), t.repo.saved.map { it.bookId to it.positionMs })
        t.controller.playback.value = PlaybackState()
        advanceTimeBy(60_000)
        assertEquals(listOf("a" to 20_000L, "other" to 10L), t.repo.saved.map { it.bookId to it.positionMs })
    }

    @Test
    fun `a finished mark made while the book plays is not undone by the next save`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 30_000)
        r.repo.markFinished("a", true)
        r.controller.playback.value = state(playing = false, pos = 31_000)
        val saved = r.repo.saved.single()
        assertTrue(saved.isFinished)
        assertEquals(31_000L, saved.positionMs)
        // it stays finished for later saves of the same loaded book
        r.controller.playback.value = state(playing = true, pos = 32_000)
        r.controller.playback.value = state(playing = false, pos = 33_000)
        assertTrue(r.repo.saved.all { it.isFinished })
    }

    @Test
    fun `mark not finished on the loaded book stays unfinished through the next saves`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 30_000)
        r.repo.markFinished("a", true)
        r.controller.playback.value = state(playing = false, pos = 31_000)
        assertTrue(r.repo.saved.last().isFinished)

        r.repo.markFinished("a", false)
        assertEquals(0L, r.repo.progress.value.single().positionMs)
        r.controller.playback.value = state(playing = true, pos = 32_000)
        r.controller.playback.value = state(playing = false, pos = 33_000)
        assertEquals(false, r.repo.saved.last().isFinished)
        r.controller.playback.value = state(playing = true, pos = 34_000)
        r.controller.playback.value = state(playing = false, pos = 35_000)
        assertEquals(false, r.repo.saved.last().isFinished)
    }

    @Test
    fun `a finished row from the server does not pin the flag`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 30_000)
        // merged from the server after the book was loaded: no user mark
        r.repo.progress.value = listOf(ReadingProgress("a", 30_000L, 100_000L, 800L, isFinished = true))
        r.controller.playback.value = state(playing = false, pos = 31_000)
        assertEquals(false, r.repo.saved.single().isFinished)
    }

    @Test
    fun `an old finished flag does not stick when the book is played again`() = runTest {
        val r = rig()
        r.repo.progress.value = listOf(ReadingProgress("a", 99_000L, 100_000L, 100L, isFinished = true))
        r.controller.playback.value = state(playing = true, pos = 1_000)
        r.controller.playback.value = state(playing = false, pos = 2_000)
        assertEquals(false, r.repo.saved.single().isFinished)
    }

    @Test
    fun `the finished mark is forgotten when the book is loaded again later`() = runTest {
        val r = rig()
        r.controller.playback.value = state(playing = true, pos = 30_000)
        r.repo.markFinished("a", true)
        r.controller.playback.value = state(playing = false, pos = 31_000)
        r.controller.playback.value = state(book = "b", playing = true, pos = 1_000)
        r.controller.playback.value = state(book = "b", playing = false, pos = 2_000)
        r.now = 900L
        r.controller.playback.value = state(book = "a", playing = true, pos = 1_000)
        r.controller.playback.value = state(book = "a", playing = false, pos = 2_000)
        assertEquals(false, r.repo.saved.last { it.bookId == "a" }.isFinished)
    }
}
