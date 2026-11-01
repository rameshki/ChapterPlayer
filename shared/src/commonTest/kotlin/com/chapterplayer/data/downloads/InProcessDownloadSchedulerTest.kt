package com.chapterplayer.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InProcessDownloadSchedulerTest {

    private class Setup(scope: TestScope, var result: () -> BookResult = { BookResult.Done }) {
        val wifi = MutableStateFlow(true)
        val mobile = MutableStateFlow(false)
        val starts = mutableListOf<String>()
        val stops = mutableListOf<String>()
        val finished = mutableListOf<Pair<String, BookResult>>()
        /** When set, a run waits for it (or for its cancellation). */
        var gate: CompletableDeferred<Unit>? = null
        val wifiOnlyAsked = mutableListOf<Boolean>()
        val scheduler = InProcessDownloadScheduler(
            scope = scope.backgroundScope,
            run = { id ->
                starts += id
                try {
                    gate?.await()
                    result()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    stops += id
                    throw e
                }
            },
            allowed = { wifiOnly ->
                wifiOnlyAsked += wifiOnly
                if (wifiOnly) wifi else MutableStateFlow(true)
            },
            onFinished = { id, r -> finished += id to r },
        )
    }

    @Test
    fun `a job runs when the network fits and is forgotten when it ends`() = runTest {
        val s = Setup(this)
        s.scheduler.schedule("b1", wifiOnly = true, replace = false)
        runCurrent()
        assertEquals(listOf("b1"), s.starts)
        assertEquals(listOf<Pair<String, BookResult>>("b1" to BookResult.Done), s.finished)
        assertFalse(s.scheduler.isScheduled("b1"))
    }

    @Test
    fun `a job waits for wifi and starts when it comes`() = runTest {
        val s = Setup(this)
        s.wifi.value = false
        s.scheduler.schedule("b1", wifiOnly = true, replace = false)
        runCurrent()
        assertTrue(s.starts.isEmpty())
        assertTrue(s.scheduler.isScheduled("b1"))
        s.wifi.value = true
        runCurrent()
        assertEquals(listOf("b1"), s.starts)
    }

    @Test
    fun `losing wifi stops the run and it carries on when wifi is back`() = runTest {
        val s = Setup(this)
        s.gate = CompletableDeferred()
        s.scheduler.schedule("b1", wifiOnly = true, replace = false)
        runCurrent()
        assertEquals(setOf("b1"), s.scheduler.active.value)
        s.wifi.value = false
        runCurrent()
        assertEquals(listOf("b1"), s.stops)
        assertTrue(s.scheduler.active.value.isEmpty())
        assertTrue(s.finished.isEmpty())
        s.gate = null
        s.wifi.value = true
        runCurrent()
        assertEquals(listOf("b1", "b1"), s.starts)
        assertEquals(listOf<Pair<String, BookResult>>("b1" to BookResult.Done), s.finished)
    }

    @Test
    fun `try again later runs again after the backoff`() = runTest {
        var calls = 0
        val s = Setup(this) { if (++calls == 1) BookResult.Retry else BookResult.Done }
        s.scheduler.schedule("b1", wifiOnly = false, replace = false)
        runCurrent()
        assertEquals(1, s.starts.size)
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, s.starts.size)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, s.starts.size)
        assertEquals(listOf<Pair<String, BookResult>>("b1" to BookResult.Done), s.finished)
    }

    @Test
    fun `a second schedule keeps the job and replace restarts it with the new rule`() = runTest {
        val s = Setup(this)
        s.gate = CompletableDeferred()
        assertTrue(s.scheduler.scheduleNew("b1", wifiOnly = true, replace = false))
        runCurrent()
        assertFalse(s.scheduler.scheduleNew("b1", wifiOnly = true, replace = false))
        runCurrent()
        assertEquals(1, s.starts.size)
        assertFalse(s.scheduler.scheduleNew("b1", wifiOnly = false, replace = true))
        runCurrent()
        assertEquals(listOf("b1"), s.stops)
        assertEquals(2, s.starts.size)
        assertEquals(false, s.wifiOnlyAsked.last())
    }

    @Test
    fun `cancel stops the job`() = runTest {
        val s = Setup(this)
        s.gate = CompletableDeferred()
        s.scheduler.schedule("b1", wifiOnly = true, replace = false)
        runCurrent()
        s.scheduler.cancel("b1")
        runCurrent()
        assertEquals(listOf("b1"), s.stops)
        assertFalse(s.scheduler.isScheduled("b1"))
        assertTrue(s.finished.isEmpty())
    }

    @Test
    fun `stop all stops every job and waits for them`() = runTest {
        val s = Setup(this)
        s.gate = CompletableDeferred()
        s.scheduler.schedule("b1", wifiOnly = true, replace = false)
        s.scheduler.schedule("b2", wifiOnly = true, replace = false)
        runCurrent()
        s.scheduler.stopAll()
        assertEquals(setOf("b1", "b2"), s.stops.toSet())
        assertTrue(s.scheduler.active.value.isEmpty())
        assertFalse(s.scheduler.isScheduled("b1"))
        assertFalse(s.scheduler.isScheduled("b2"))
    }

    @Test
    fun `a job that throws is tried again later instead of ending`() = runTest {
        var calls = 0
        val s = Setup(this) { if (++calls == 1) error("bug") else BookResult.Done }
        s.scheduler.schedule("b1", wifiOnly = false, replace = false)
        runCurrent()
        assertTrue(s.finished.isEmpty())
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(listOf<Pair<String, BookResult>>("b1" to BookResult.Done), s.finished)
    }

    @Test
    fun `a book is active only while it runs`() = runTest {
        val s = Setup(this)
        s.gate = CompletableDeferred()
        s.scheduler.schedule("b1", wifiOnly = false, replace = false)
        runCurrent()
        assertEquals(setOf("b1"), s.scheduler.active.value)
        s.gate!!.complete(Unit)
        runCurrent()
        assertTrue(s.scheduler.active.value.isEmpty())
    }
}
