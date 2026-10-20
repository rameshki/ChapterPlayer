package com.chapterplayer.data.bookmarks

import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecorderTest {

    private class Rig(scope: CoroutineScope, now: () -> Long) {
        val repo = FakeBookRepository()
        val controller = FakePlaybackController()
        val recorder = HistoryRecorder(controller, repo, scope, now)
        fun emit(
            pos: Long, book: String? = "a", playing: Boolean = true, speed: Float = 1f, duration: Long = 3_600_000
        ) {
            controller.playback.value = PlaybackState(
                bookId = book, isPlaying = playing, positionMs = pos, durationMs = duration, speed = speed
            )
        }
    }

    private fun TestScope.rig() = Rig(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        { testScheduler.currentTime }
    ).also { it.recorder.start() }

    @Test
    fun `normal playback records nothing`() = runTest {
        val r = rig()
        r.emit(100_000)
        repeat(20) {
            advanceTimeBy(1_000)
            r.emit(100_000 + (it + 1) * 1_000L)
        }
        assertTrue(r.repo.history.value.isEmpty())
    }

    @Test
    fun `a slider jump records the position that was left`() = runTest {
        val r = rig()
        r.emit(100_000)
        advanceTimeBy(1_000)
        r.emit(900_000)
        assertEquals(listOf(100_000L), r.repo.history.value.map { it.positionMs })
        assertEquals("a", r.repo.history.value.single().bookId)
        assertEquals(1_000L, r.repo.history.value.single().recordedAt)
    }

    @Test
    fun `a jump back is recorded too`() = runTest {
        val r = rig()
        r.emit(900_000)
        advanceTimeBy(500)
        r.emit(100_000)
        assertEquals(listOf(900_000L), r.repo.history.value.map { it.positionMs })
    }

    @Test
    fun `a chapter jump records the position left`() = runTest {
        val r = rig()
        r.emit(595_000)
        advanceTimeBy(500)
        r.emit(1_200_000)
        assertEquals(listOf(595_000L), r.repo.history.value.map { it.positionMs })
    }

    @Test
    fun `a jump while paused is recorded`() = runTest {
        val r = rig()
        r.emit(100_000, playing = false)
        advanceTimeBy(5_000)
        r.emit(700_000, playing = false)
        assertEquals(listOf(100_000L), r.repo.history.value.map { it.positionMs })
    }

    @Test
    fun `playback at one and a half speed is not a jump`() = runTest {
        val r = rig()
        r.emit(100_000, speed = 1.5f)
        for (i in 1..10) {
            advanceTimeBy(10_000)
            r.emit(100_000 + i * 15_000L, speed = 1.5f)
        }
        assertTrue(r.repo.history.value.isEmpty())
    }

    @Test
    fun `the first emission and a book switch record nothing`() = runTest {
        val r = rig()
        r.emit(100_000, book = "a")
        advanceTimeBy(1_000)
        r.emit(900_000, book = "b")
        advanceTimeBy(1_000)
        r.emit(100_000, book = null)
        advanceTimeBy(1_000)
        r.emit(900_000, book = "b")
        advanceTimeBy(1_000)
        r.emit(100_000, book = "a")
        assertTrue(r.repo.recordAttempts.isEmpty())
    }

    @Test
    fun `an emission without a duration is ignored and resets the baseline`() = runTest {
        val r = rig()
        r.emit(100_000)
        advanceTimeBy(1_000)
        r.emit(0, duration = 0)
        assertTrue(r.repo.recordAttempts.isEmpty())
        // The player is ready again at a different place: that is a start, not a jump.
        advanceTimeBy(1_000)
        r.emit(900_000)
        assertTrue(r.repo.recordAttempts.isEmpty())
        // The next jump counts again.
        advanceTimeBy(1_000)
        r.emit(100_000)
        assertEquals(listOf(900_000L), r.repo.history.value.map { it.positionMs })
    }

    @Test
    fun `two quick jumps near each other keep one entry`() = runTest {
        val r = rig()
        r.emit(100_000)
        advanceTimeBy(500)
        r.emit(900_000)
        advanceTimeBy(500)
        r.emit(102_000)
        assertEquals(listOf(900_000L, 100_000L), r.repo.history.value.map { it.positionMs })
        advanceTimeBy(500)
        r.emit(900_000)
        // Left 102_000: within 5 s of the entry at 100_000, which it replaces.
        assertEquals(listOf(102_000L, 900_000L), r.repo.history.value.map { it.positionMs })
    }

    @Test
    fun `a failing record does not stop the recorder`() = runTest {
        val r = rig()
        r.repo.onRecordHistory = { error("db down") }
        r.emit(100_000)
        advanceTimeBy(500)
        r.emit(900_000)
        advanceTimeBy(500)
        r.emit(100_000)
        assertEquals(2, r.repo.recordAttempts.size)
    }

    @Test
    fun `starting twice records once`() = runTest {
        val r = rig()
        r.recorder.start()
        r.emit(100_000)
        advanceTimeBy(500)
        r.emit(900_000)
        assertEquals(1, r.repo.recordAttempts.size)
    }
}
