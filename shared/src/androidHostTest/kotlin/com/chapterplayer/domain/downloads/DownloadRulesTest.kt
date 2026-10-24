package com.chapterplayer.domain.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRulesTest {
    private val mib = 1024L * 1024

    @Test
    fun `overall fraction is done over total`() {
        assertEquals(0.5f, overallFraction(listOf(FileProgress(100, 100), FileProgress(100, 0))))
    }

    @Test
    fun `overall fraction is zero for no files or zero total`() {
        assertEquals(0f, overallFraction(emptyList()))
        assertEquals(0f, overallFraction(listOf(FileProgress(0, 0))))
    }

    @Test
    fun `overall fraction is clamped`() {
        assertEquals(1f, overallFraction(listOf(FileProgress(10, 50))))
        assertEquals(0f, overallFraction(listOf(FileProgress(10, -5))))
    }

    @Test
    fun `speed is zero with fewer than two samples`() {
        val t = SpeedTracker()
        assertEquals(0.0, t.bytesPerSecond())
        t.add(1000, 0)
        assertEquals(0.0, t.bytesPerSecond())
    }

    @Test
    fun `speed is zero when no time passed`() {
        val t = SpeedTracker()
        t.add(1000, 0)
        t.add(1000, 500)
        assertEquals(0.0, t.bytesPerSecond())
    }

    @Test
    fun `speed is bytes over seconds across the window`() {
        val t = SpeedTracker()
        t.add(0, 0)
        t.add(2000, 1000)
        t.add(4000, 3000)
        assertEquals(750.0, t.bytesPerSecond(), 0.001)
    }

    @Test
    fun `old samples fall out of the window`() {
        val t = SpeedTracker(windowMs = 10_000)
        t.add(0, 0)
        t.add(5_000, 100_000)
        t.add(20_000, 100_000)
        t.add(30_000, 110_000)
        // Only the samples at 20 s and 30 s are within 10 s of the newest.
        assertEquals(1000.0, t.bytesPerSecond(), 0.001)
    }

    @Test
    fun `a counter that goes backwards resets the window`() {
        val t = SpeedTracker()
        t.add(0, 0)
        t.add(1000, 5000)
        t.add(2000, 100)
        assertEquals(0.0, t.bytesPerSecond())
        t.add(3000, 1100)
        assertEquals(1000.0, t.bytesPerSecond(), 0.001)
    }

    @Test
    fun `eta rules`() {
        assertNull(etaSeconds(1000, 0.0))
        assertNull(etaSeconds(1000, -1.0))
        assertEquals(0L, etaSeconds(0, 0.0))
        assertEquals(0L, etaSeconds(0, 100.0))
        assertEquals(0L, etaSeconds(-5, 100.0))
        assertEquals(10L, etaSeconds(1000, 100.0))
        assertEquals(11L, etaSeconds(1001, 100.0))
    }

    @Test
    fun `next file is the first incomplete one`() {
        assertEquals(1, nextFileIndex(listOf(FileProgress(10, 10), FileProgress(10, 3), FileProgress(10, 0))))
        assertNull(nextFileIndex(listOf(FileProgress(10, 10), FileProgress(10, 10))))
        assertNull(nextFileIndex(emptyList()))
    }

    @Test
    fun `resume offset is the smaller of file length and record when both valid`() {
        assertEquals(400L, validResumeOffset(500, 400, 1000))
        assertEquals(300L, validResumeOffset(300, 400, 1000))
        assertEquals(0L, validResumeOffset(0, 0, 1000))
        assertEquals(1000L, validResumeOffset(1000, 1000, 1000))
    }

    @Test
    fun `resume offset is zero when either is out of range`() {
        assertEquals(0L, validResumeOffset(1001, 400, 1000))
        assertEquals(0L, validResumeOffset(500, 1001, 1000))
        assertEquals(0L, validResumeOffset(-1, 400, 1000))
        assertEquals(0L, validResumeOffset(500, -1, 1000))
    }

    @Test
    fun `backoff doubles from five seconds and caps at five minutes`() {
        assertEquals(5_000L, backoffMs(1))
        assertEquals(10_000L, backoffMs(2))
        assertEquals(20_000L, backoffMs(3))
        assertEquals(300_000L, backoffMs(7))
        assertEquals(300_000L, backoffMs(100))
        assertEquals(5_000L, backoffMs(0))
        assertEquals(5_000L, backoffMs(-4))
    }

    @Test
    fun `network policy`() {
        assertTrue(mayDownload(NetworkPolicy.WIFI_ONLY, onUnmetered = true, online = true))
        assertFalse(mayDownload(NetworkPolicy.WIFI_ONLY, onUnmetered = false, online = true))
        assertTrue(mayDownload(NetworkPolicy.WIFI_AND_CELLULAR, onUnmetered = false, online = true))
        assertFalse(mayDownload(NetworkPolicy.WIFI_AND_CELLULAR, onUnmetered = true, online = false))
        assertFalse(mayDownload(NetworkPolicy.WIFI_ONLY, onUnmetered = true, online = false))
    }

    @Test
    fun `room for a download`() {
        assertTrue(hasRoomFor(neededBytes = 100 * mib, freeBytes = 200 * mib, limitBytes = null, usedBytes = 0))
        assertFalse(hasRoomFor(100 * mib, 200 * mib - 1, null, 0))
        assertTrue(hasRoomFor(100, 1000, 500, 400, safetyBytes = 0))
        assertFalse(hasRoomFor(100, 1000, 500, 401, safetyBytes = 0))
        assertTrue(hasRoomFor(100, 100, null, 0, safetyBytes = 0))
    }
}
