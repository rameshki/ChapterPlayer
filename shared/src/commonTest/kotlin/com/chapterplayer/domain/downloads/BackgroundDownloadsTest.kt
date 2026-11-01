package com.chapterplayer.domain.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundDownloadsTest {

    @Test
    fun `the retry wait starts at 30 s - doubles and stops at 5 hours`() {
        assertEquals(30_000L, schedulerRetryDelayMs(0))
        assertEquals(30_000L, schedulerRetryDelayMs(1))
        assertEquals(60_000L, schedulerRetryDelayMs(2))
        assertEquals(120_000L, schedulerRetryDelayMs(3))
        assertEquals(5L * 60 * 60 * 1000, schedulerRetryDelayMs(40))
    }

    @Test
    fun `wifi only blocks a metered network and nothing runs offline`() {
        assertTrue(downloadAllowedOn(wifiOnly = true, online = true, metered = false))
        assertFalse(downloadAllowedOn(wifiOnly = true, online = true, metered = true))
        assertTrue(downloadAllowedOn(wifiOnly = false, online = true, metered = true))
        assertFalse(downloadAllowedOn(wifiOnly = false, online = false, metered = false))
    }

    @Test
    fun `background time is held only in the background while a download transfers`() {
        assertEquals(BackgroundTimeStep.BEGIN, backgroundTimeStep(inBackground = true, downloading = true, holding = false))
        assertEquals(BackgroundTimeStep.NONE, backgroundTimeStep(inBackground = true, downloading = true, holding = true))
        assertEquals(BackgroundTimeStep.END, backgroundTimeStep(inBackground = true, downloading = false, holding = true))
        assertEquals(BackgroundTimeStep.END, backgroundTimeStep(inBackground = false, downloading = true, holding = true))
        assertEquals(BackgroundTimeStep.NONE, backgroundTimeStep(inBackground = false, downloading = true, holding = false))
        assertEquals(BackgroundTimeStep.NONE, backgroundTimeStep(inBackground = true, downloading = false, holding = false))
    }

    @Test
    fun `a continued task is asked for only for a new job started in front with the api there`() {
        assertTrue(shouldRequestContinuedTask(appActive = true, newJob = true, apiAvailable = true, taskRunning = false))
        assertFalse(shouldRequestContinuedTask(appActive = false, newJob = true, apiAvailable = true, taskRunning = false))
        assertFalse(shouldRequestContinuedTask(appActive = true, newJob = false, apiAvailable = true, taskRunning = false))
        assertFalse(shouldRequestContinuedTask(appActive = true, newJob = true, apiAvailable = false, taskRunning = false))
        assertFalse(shouldRequestContinuedTask(appActive = true, newJob = true, apiAvailable = true, taskRunning = true))
    }

    @Test
    fun `the continued task names the book and shows its progress line`() {
        val text = continuedTaskText(listOf("b1"), mapOf("b1" to "A Book"), mapOf("b1" to BookProgress(0.42f, 1000.0, 600)))
        assertEquals("Downloading A Book", text.title)
        assertEquals("42% · 10m left", text.subtitle)
        assertEquals(420L, text.completedPerMille)
    }

    @Test
    fun `several books show a count and the average`() {
        val text = continuedTaskText(
            listOf("b1", "b2"), mapOf("b1" to "A", "b2" to "B"),
            mapOf("b1" to BookProgress(1f, 0.0, 0), "b2" to BookProgress(0f, 0.0, null)),
        )
        assertEquals("Downloading 2 books", text.title)
        assertEquals("50%", text.subtitle)
        assertEquals(500L, text.completedPerMille)
    }

    @Test
    fun `a book without progress yet is starting`() {
        val text = continuedTaskText(listOf("b1"), emptyMap(), emptyMap())
        assertEquals("Downloading Audiobook", text.title)
        assertEquals("Starting", text.subtitle)
        assertEquals(0L, text.completedPerMille)
    }

    @Test
    fun `a notice only in the background and only for finished or failed`() {
        assertNull(downloadEndNotice("A", DownloadEnd.DONE, null, appActive = true))
        assertEquals(DownloadNotice("Download finished", "A is ready to play offline."), downloadEndNotice("A", DownloadEnd.DONE, null, appActive = false))
        assertEquals(DownloadNotice("Download failed", "A: Not enough space"), downloadEndNotice("A", DownloadEnd.FAILED, "Not enough space", appActive = false))
        assertNull(downloadEndNotice("A", DownloadEnd.OTHER, null, appActive = false))
    }
}

class PathInContainerTest {
    private val now = "/var/mobile/Containers/Data/Application/NEW/Library/Application Support"

    @Test
    fun `a path of an earlier container moves into the current one`() {
        assertEquals(
            "$now/downloads/srv1/abs_1/77.mp3",
            pathInContainer("/var/mobile/Containers/Data/Application/OLD/Library/Application Support/downloads/srv1/abs_1/77.mp3", now),
        )
    }

    @Test
    fun `a current path stays the same`() {
        assertEquals("$now/downloads/a.mp3", pathInContainer("$now/downloads/a.mp3", now))
    }

    @Test
    fun `a path outside application support is left alone`() {
        assertEquals("/tmp/x.mp3", pathInContainer("/tmp/x.mp3", now))
    }
}
