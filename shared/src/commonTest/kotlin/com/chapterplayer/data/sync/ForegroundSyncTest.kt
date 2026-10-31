package com.chapterplayer.data.sync

import com.chapterplayer.data.connectivity.ConnectivityMonitor
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
class ForegroundSyncTest {

    private class Setup(scope: TestScope) {
        var passes = 0
        var failNext = false
        val network = MutableStateFlow<String?>("wifi-1")
        val online = MutableStateFlow(true)
        /** A pass made while the device turned out to be offline (every server failed). */
        var goOfflineDuringPass = false
        val sync = ForegroundSync(
            target = {
                passes++
                if (goOfflineDuringPass) {
                    goOfflineDuringPass = false
                    online.value = false
                }
                if (failNext) {
                    failNext = false
                    error("server down")
                }
            },
            connectivity = object : ConnectivityMonitor { override val isOnline = online },
            scope = scope.backgroundScope,
            nowMs = { scope.testScheduler.currentTime },
            minGapMs = 60_000L,
        )
    }

    @Test
    fun `the first pass runs at start`() = runTest {
        val s = Setup(this)
        s.sync.start(s.network)
        runCurrent()
        assertEquals(1, s.passes)
    }

    @Test
    fun `becoming active right after the start is skipped and later it syncs again`() = runTest {
        val s = Setup(this)
        s.sync.start(s.network)
        runCurrent()
        s.sync.onAppActive()
        runCurrent()
        assertEquals(1, s.passes)
        advanceTimeBy(60_000L)
        s.sync.onAppActive()
        runCurrent()
        assertEquals(2, s.passes)
    }

    @Test
    fun `another network syncs and losing the network does not`() = runTest {
        val s = Setup(this)
        s.sync.start(s.network)
        runCurrent()
        advanceTimeBy(60_000L)
        s.network.value = null
        runCurrent()
        assertEquals(1, s.passes)
        s.network.value = "cell"
        runCurrent()
        assertEquals(2, s.passes)
    }

    @Test
    fun `a flapping network within the gap syncs once`() = runTest {
        val s = Setup(this)
        s.sync.start(s.network)
        runCurrent()
        advanceTimeBy(60_000L)
        s.network.value = "cell"
        runCurrent()
        s.network.value = "wifi-1"
        runCurrent()
        assertEquals(2, s.passes)
    }

    @Test
    fun `a failing pass does not stop later ones`() = runTest {
        val s = Setup(this)
        s.failNext = true
        s.sync.start(s.network)
        runCurrent()
        advanceTimeBy(60_000L)
        s.sync.onAppActive()
        runCurrent()
        assertEquals(2, s.passes)
    }

    @Test
    fun `launched offline - the network returning inside the gap syncs at once`() = runTest {
        val s = Setup(this)
        s.online.value = false
        s.network.value = null
        s.sync.start(s.network)
        runCurrent()
        // Offline: nothing is tried, so no failure banner and the gap is not used up.
        assertEquals(0, s.passes)
        advanceTimeBy(10_000L)
        s.online.value = true
        s.network.value = "wifi-1"
        runCurrent()
        assertEquals(1, s.passes)
    }

    @Test
    fun `offline the app becoming active tries nothing and the first active moment online syncs`() = runTest {
        val s = Setup(this)
        s.online.value = false
        s.sync.start(s.network)
        s.sync.onAppActive()
        runCurrent()
        assertEquals(0, s.passes)
        s.online.value = true
        s.sync.onAppActive()
        runCurrent()
        assertEquals(1, s.passes)
    }

    @Test
    fun `a pass that ended offline is made again as soon as the network returns - even inside the gap`() = runTest {
        val s = Setup(this)
        s.goOfflineDuringPass = true
        s.sync.start(s.network)
        runCurrent()
        assertEquals(1, s.passes)
        s.network.value = null
        runCurrent()
        advanceTimeBy(5_000L)
        s.online.value = true
        s.network.value = "wifi-1"
        runCurrent()
        assertEquals(2, s.passes)
        // That retry used the gap: a further change within it waits.
        s.network.value = "cell"
        runCurrent()
        assertEquals(2, s.passes)
    }

    @Test
    fun `starting twice does nothing more`() = runTest {
        val s = Setup(this)
        s.sync.start(s.network)
        s.sync.start(s.network)
        runCurrent()
        assertEquals(1, s.passes)
    }

    @Test
    fun `a pass is due the first time after the gap and when the clock went back`() {
        assertTrue(foregroundSyncDue(null, 0L, 1_000L))
        assertFalse(foregroundSyncDue(5_000L, 5_999L, 1_000L))
        assertTrue(foregroundSyncDue(5_000L, 6_000L, 1_000L))
        assertTrue(foregroundSyncDue(5_000L, 1_000L, 1_000L))
    }
}
