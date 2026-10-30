package com.chapterplayer.domain.downloads

import com.chapterplayer.domain.downloads.DownloadStatus.DONE
import com.chapterplayer.domain.downloads.DownloadStatus.DOWNLOADING
import com.chapterplayer.domain.downloads.DownloadStatus.FAILED
import com.chapterplayer.domain.downloads.DownloadStatus.PAUSED
import com.chapterplayer.domain.downloads.DownloadStatus.QUEUED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadJobRulesTest {

    @Test
    fun `the notification shows the percent and the time left`() {
        assertEquals("42% · 3h 10m left", downloadNotificationText(BookProgress(0.42f, 1.0, 11_400)))
        assertEquals("5% · 12m left", downloadNotificationText(BookProgress(0.05f, 1.0, 12 * 60)))
        assertEquals("99% · 1m left", downloadNotificationText(BookProgress(0.999f, 1.0, 20)))
        assertEquals("10% · 2h left", downloadNotificationText(BookProgress(0.1f, 1.0, 7_200)))
        assertEquals("30% · 30h 5m left", downloadNotificationText(BookProgress(0.3f, 1.0, 30 * 3_600 + 5 * 60)))
    }

    @Test
    fun `the notification shows only the percent while the speed is unknown`() {
        assertEquals("42%", downloadNotificationText(BookProgress(0.42f, 0.0, null)))
        assertEquals("100%", downloadNotificationText(BookProgress(1f, 0.0, 0)))
        assertEquals("Starting", downloadNotificationText(null))
    }

    @Test
    fun `time left rounds up to whole minutes`() {
        assertEquals("1m", formatTimeLeft(1))
        assertEquals("1m", formatTimeLeft(60))
        assertEquals("2m", formatTimeLeft(61))
        assertEquals("1h", formatTimeLeft(3_600))
        assertEquals("1h 1m", formatTimeLeft(3_601))
    }

    @Test
    fun `a stopped job puts a running download back in the queue and leaves the rest alone`() {
        assertEquals(QUEUED, statusAfterStop(DOWNLOADING))
        for (status in listOf(QUEUED, PAUSED, FAILED, DONE, null)) assertNull(statusAfterStop(status))
    }

    @Test
    fun `only a download stopped by the time limit that is still queued re-enqueues itself`() {
        assertTrue(reenqueueAfterStop(timedOut = true, status = QUEUED))
        assertTrue(reenqueueAfterStop(timedOut = true, status = DOWNLOADING))
        assertFalse(reenqueueAfterStop(timedOut = false, status = QUEUED))
        for (status in listOf(PAUSED, FAILED, DONE, null)) assertFalse(reenqueueAfterStop(timedOut = true, status = status))
    }

    @Test
    fun `app start resumes queued and running downloads only`() {
        assertTrue(resumesOnStart(QUEUED))
        assertTrue(resumesOnStart(DOWNLOADING))
        for (status in listOf(PAUSED, FAILED, DONE)) assertFalse(resumesOnStart(status))
    }

    @Test
    fun `enqueue starts a new or stopped download and leaves a finished one`() {
        assertEquals(QUEUED, statusOnEnqueue(null))
        assertEquals(QUEUED, statusOnEnqueue(FAILED))
        assertEquals(QUEUED, statusOnEnqueue(PAUSED))
        assertEquals(QUEUED, statusOnEnqueue(QUEUED))
        assertEquals(DOWNLOADING, statusOnEnqueue(DOWNLOADING))
        assertNull(statusOnEnqueue(DONE))
    }

    @Test
    fun `resume only restarts a paused or failed download`() {
        assertEquals(QUEUED, statusOnResume(PAUSED))
        assertEquals(QUEUED, statusOnResume(FAILED))
        assertNull(statusOnResume(DONE))
        assertNull(statusOnResume(null))
        assertEquals(QUEUED, statusOnResume(QUEUED))
        assertEquals(DOWNLOADING, statusOnResume(DOWNLOADING))
    }

    @Test
    fun `pause applies to downloads that are not finished`() {
        assertTrue(canPause(QUEUED))
        assertTrue(canPause(DOWNLOADING))
        assertTrue(canPause(FAILED))
        assertFalse(canPause(PAUSED))
        assertFalse(canPause(DONE))
        assertFalse(canPause(null))
    }

    @Test
    fun `wi-fi only maps to the network policy`() {
        assertEquals(NetworkPolicy.WIFI_ONLY, networkPolicy(wifiOnly = true))
        assertEquals(NetworkPolicy.WIFI_AND_CELLULAR, networkPolicy(wifiOnly = false))
    }

    @Test
    fun `status names read back - and unknown names are null`() {
        assertEquals(PAUSED, downloadStatusOf("PAUSED"))
        assertNull(downloadStatusOf("SOMETHING"))
        assertNull(downloadStatusOf(null))
    }
}
