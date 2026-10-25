package com.chapterplayer.data.stats

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.stats.DayZone
import com.chapterplayer.domain.stats.MS_PER_DAY
import com.chapterplayer.testing.FakeListeningLog
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
class ListeningRecorderTest {

    private val day = 20_752
    private val owners = mapOf("abs_a" to BookOwner("s1", ServerSource.ABS), "plex_b" to BookOwner("p1", ServerSource.PLEX))

    private class Rig(val scope: TestScope, val wallStart: Long, zone: DayZone, owners: Map<String, BookOwner>) {
        val log = FakeListeningLog()
        val controller = FakePlaybackController()
        var position = 0L
        var lookups = 0
        val recorder = ListeningRecorder(
            controller, log, { id -> lookups++; owners[id] },
            CoroutineScope(scope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(scope.testScheduler)),
            zone,
            object : ListeningClock {
                override fun monoMs() = scope.testScheduler.currentTime
                override fun nowMs() = wallStart + scope.testScheduler.currentTime
            },
        )

        fun emit(book: String? = "abs_a", playing: Boolean = true, speed: Float = 1f, buffering: Boolean = false, duration: Long = 36_000_000) {
            controller.playback.value = PlaybackState(
                bookId = book, isPlaying = playing, positionMs = position, durationMs = duration, speed = speed, isBuffering = buffering
            )
        }

        /** Plays for [seconds] with a tick every half second, as the Android controller does. */
        fun play(seconds: Int, book: String = "abs_a", speed: Float = 1f) {
            emit(book, speed = speed)
            repeat(seconds * 2) {
                scope.advanceTimeBy(500)
                position += (500 * speed).toLong()
                emit(book, speed = speed)
            }
        }
    }

    private fun TestScope.rig(wallStart: Long = day * MS_PER_DAY + 10 * 3_600_000L, zone: DayZone = DayZone.UTC) =
        Rig(this, wallStart, zone, owners).also { it.recorder.start() }

    @Test
    fun `listening is written when playback pauses`() = runTest {
        val r = rig()
        r.play(12)
        r.emit(playing = false)
        assertEquals(mapOf((day to "abs_a") to 12L), r.log.wall())
        val row = r.log.log.value.single()
        assertEquals("s1", row.serverId)
        assertEquals(ServerSource.ABS, row.source)
        assertEquals(12L, row.bookSeconds)
    }

    @Test
    fun `while playing the log is written about every thirty seconds`() = runTest {
        val r = rig()
        r.play(29)
        assertTrue(r.log.log.value.isEmpty())
        r.play(2)
        // Thirty seconds after listening began, the whole seconds so far are written.
        assertEquals(30L, r.log.wall()[day to "abs_a"])
        r.play(10)
        // Not yet again: the next write is thirty seconds after the last.
        assertEquals(30L, r.log.wall()[day to "abs_a"])
        r.emit(playing = false)
        assertEquals(41L, r.log.wall()[day to "abs_a"])
    }

    @Test
    fun `faster playback logs more book time than wall time`() = runTest {
        val r = rig()
        r.play(20, speed = 1.5f)
        r.emit(playing = false, speed = 1.5f)
        val row = r.log.log.value.single()
        assertEquals(20L, row.wallSeconds)
        assertEquals(30L, row.bookSeconds)
    }

    @Test
    fun `a seek, a long gap and buffering add nothing`() = runTest {
        val r = rig()
        r.play(4)
        // A seek: the position jumps.
        advanceTimeBy(500); r.position += 600_000; r.emit()
        // A stall: buffering for a while.
        r.emit(buffering = true)
        advanceTimeBy(3_000); r.emit(buffering = true)
        // The app was asleep for a minute.
        r.emit()
        advanceTimeBy(60_000); r.position += 60_000; r.emit()
        r.emit(playing = false)
        assertEquals(mapOf((day to "abs_a") to 4L), r.log.wall())
    }

    @Test
    fun `time after midnight goes to the new day in the device zone`() = runTest {
        // 23:59:50 local time in a zone two hours east of UTC.
        val zone = DayZone { 2 * 3_600_000L }
        val wallStart = (day + 1) * MS_PER_DAY - 2 * 3_600_000L - 10_000L
        val r = rig(wallStart, zone)
        r.play(20)
        r.emit(playing = false)
        assertEquals(mapOf((day to "abs_a") to 10L, (day + 1 to "abs_a") to 10L), r.log.wall())
    }

    @Test
    fun `each book has its own row with its own server`() = runTest {
        val r = rig()
        r.play(5, book = "abs_a")
        r.emit(book = "abs_a", playing = false)
        r.position = 0
        r.play(7, book = "plex_b")
        r.emit(book = "plex_b", playing = false)
        assertEquals(mapOf((day to "abs_a") to 5L, (day to "plex_b") to 7L), r.log.wall())
        assertEquals(ServerSource.PLEX, r.log.log.value.single { it.bookId == "plex_b" }.source)
        assertEquals("p1", r.log.log.value.single { it.bookId == "plex_b" }.serverId)
        // The owner of each book is looked up once.
        assertEquals(2, r.lookups)
    }

    @Test
    fun `a book the cache does not know is logged by its id prefix`() = runTest {
        val r = rig()
        r.play(3, book = "plex_zzz")
        r.emit(book = "plex_zzz", playing = false)
        val row = r.log.log.value.single()
        assertEquals(ServerSource.PLEX, row.source)
        assertEquals("", row.serverId)
    }

    @Test
    fun `parts of a second carry over to the next write`() = runTest {
        val r = rig()
        repeat(4) {
            r.emit()
            advanceTimeBy(500); r.position += 500; r.emit()
            advanceTimeBy(250); r.position += 250; r.emit(playing = false)
            advanceTimeBy(10_000)
        }
        // Four times 0.75 s.
        assertEquals(3L, r.log.wall()[day to "abs_a"])
    }

    @Test
    fun `a failing write keeps the time for the next one`() = runTest {
        val r = rig()
        r.log.onAdd = { error("disk full") }
        r.play(5)
        r.emit(playing = false)
        assertTrue(r.log.log.value.isEmpty())
        r.log.onAdd = {}
        r.play(5)
        r.emit(playing = false)
        assertEquals(10L, r.log.wall()[day to "abs_a"])
    }

    @Test
    fun `stopping playback writes what was pending`() = runTest {
        val r = rig()
        r.play(6)
        r.emit(book = null, playing = false, duration = 0)
        assertEquals(6L, r.log.wall()[day to "abs_a"])
    }

    @Test
    fun `nothing is written when nothing was heard`() = runTest {
        val r = rig()
        r.emit(playing = false)
        advanceTimeBy(1_000)
        r.emit(playing = false)
        assertEquals(0, r.log.addCalls)
    }

    @Test
    fun `starting twice records once`() = runTest {
        val r = rig()
        r.recorder.start()
        r.play(4)
        r.emit(playing = false)
        assertEquals(4L, r.log.wall()[day to "abs_a"])
    }
}
