package com.chapterplayer.domain.downloads

import com.chapterplayer.domain.downloads.DownloadStatus.DONE
import com.chapterplayer.domain.downloads.DownloadStatus.DOWNLOADING
import com.chapterplayer.domain.downloads.DownloadStatus.FAILED
import com.chapterplayer.domain.downloads.DownloadStatus.PAUSED
import com.chapterplayer.domain.downloads.DownloadStatus.QUEUED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadTimeLimitTest {

    @Test
    fun `a stop that is not the time limit leaves the rest to WorkManager`() {
        assertEquals(AfterJobStop.NOTHING, afterJobStop(timedOut = false, waitingBooks = 3, appVisible = false))
        assertEquals(AfterJobStop.NOTHING, afterJobStop(timedOut = false, waitingBooks = 3, appVisible = true))
    }

    @Test
    fun `the time limit with nothing waiting does nothing and tells nobody`() {
        assertEquals(AfterJobStop.NOTHING, afterJobStop(timedOut = true, waitingBooks = 0, appVisible = false))
        assertEquals(AfterJobStop.NOTHING, afterJobStop(timedOut = true, waitingBooks = 0, appVisible = true))
    }

    @Test
    fun `the time limit in the background holds the queue until the app opens`() {
        assertEquals(AfterJobStop.HOLD_UNTIL_APP_OPENS, afterJobStop(timedOut = true, waitingBooks = 1, appVisible = false))
        assertEquals(AfterJobStop.HOLD_UNTIL_APP_OPENS, afterJobStop(timedOut = true, waitingBooks = 4, appVisible = false))
    }

    @Test
    fun `the time limit with the app in front continues at once`() {
        assertEquals(AfterJobStop.CONTINUE_NOW, afterJobStop(timedOut = true, waitingBooks = 2, appVisible = true))
    }

    @Test
    fun `only waiting and running downloads are held`() {
        assertTrue(heldByTimeLimit(QUEUED))
        assertTrue(heldByTimeLimit(DOWNLOADING))
        for (status in listOf(PAUSED, FAILED, DONE, null)) assertFalse(heldByTimeLimit(status))
    }

    @Test
    fun `the hold waits a whole day so the used time has left the window`() {
        assertEquals(24L * 60 * 60 * 1000, TIME_LIMIT_HOLD_MS)
    }

    @Test
    fun `opening the app releases a recorded hold only`() {
        assertTrue(releaseHoldOnAppOpen(heldAtMs = 1_000L))
        assertTrue(releaseHoldOnAppOpen(heldAtMs = 0L))
        assertFalse(releaseHoldOnAppOpen(heldAtMs = null))
    }

    @Test
    fun `the notice says downloads paused and that opening the app continues them`() {
        val one = timeLimitNotice(1)
        assertEquals("Downloads paused", one.title)
        assertEquals("Android allows downloads in the background for 6 hours a day. Open ChapterPlayer to continue the download.", one.text)
        assertEquals(
            "Android allows downloads in the background for 6 hours a day. Open ChapterPlayer to continue 3 downloads.",
            timeLimitNotice(3).text,
        )
        assertEquals(one, timeLimitNotice(0))
    }
}
