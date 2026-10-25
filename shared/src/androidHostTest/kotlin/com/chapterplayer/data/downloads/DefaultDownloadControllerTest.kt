package com.chapterplayer.data.downloads

import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.data.repository.InMemoryDownloadDao
import com.chapterplayer.domain.downloads.DownloadStatus
import com.chapterplayer.testing.FakeAppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultDownloadControllerTest {

    private val root: File = Files.createTempDirectory("dlctl").toFile()
    private val bookId = "abs_li_1"

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class FakeScheduler : DownloadScheduler {
        val log = mutableListOf<String>()
        override suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean) {
            log += "schedule $bookId wifiOnly=$wifiOnly replace=$replace"
        }
        override suspend fun cancel(bookId: String) { log += "cancel $bookId" }
    }

    private class FakeJobs(val log: MutableList<String>) : DownloadJobs {
        override suspend fun <T> whileIdle(bookId: String, block: suspend () -> T): T {
            log += "idle $bookId"
            return block()
        }
    }

    private fun book(id: String = bookId) = BookEntity(
        id = id, title = "T", author = "A", narrator = "N", durationMs = 1, chaptersJson = "[]",
        streamUrl = "", coverUrl = null, source = "ABS", serverId = "srv1", cachedAt = 0
    )

    private fun row(status: DownloadStatus, id: String = bookId) = DownloadEntity(id, "srv1", status.name, 100, "old error", 0)

    private inner class Rig(
        rows: List<DownloadEntity> = emptyList(),
        files: List<DownloadFileEntity> = emptyList(),
        currentRoot: File = root,
    ) {
        val dao = InMemoryDownloadDao(rows, files)
        val settings = FakeAppSettings()
        val scheduler = FakeScheduler()
        val location = object : DownloadLocation {
            override suspend fun rootDir() = currentRoot
            override suspend fun freeBytes() = Long.MAX_VALUE
        }
        /** Books the player was told to let go of, with the files still on disk at that moment. */
        val released = mutableListOf<Pair<String, Boolean>>()
        val controller = DefaultDownloadController(
            dao, FakeBookDao(book()), settings, location, scheduler, FakeJobs(scheduler.log),
            removal = { id -> released += id to bookDirectory(currentRoot, "srv1", id).exists() }, nowMs = { 42 }
        )
        fun status() = dao.downloads.value.singleOrNull { it.bookId == bookId }?.status
    }

    @Test
    fun `enqueue adds a queued row for the book's server and schedules it on wi-fi`() = runTest {
        val rig = Rig()

        rig.controller.enqueue(bookId)

        assertEquals(DownloadEntity(bookId, "srv1", "QUEUED", 0, null, 42), rig.dao.downloads.value.single())
        assertEquals(listOf("schedule $bookId wifiOnly=true replace=false"), rig.scheduler.log)
    }

    @Test
    fun `with wi-fi only off the work may use any network`() = runTest {
        val rig = Rig()
        rig.settings.wifiOnly.value = false

        rig.controller.enqueue(bookId)

        assertEquals(listOf("schedule $bookId wifiOnly=false replace=false"), rig.scheduler.log)
    }

    @Test
    fun `enqueue leaves a finished download and an unknown book alone`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.DONE)))

        rig.controller.enqueue(bookId)
        rig.controller.enqueue("abs_unknown")

        assertEquals("DONE", rig.status())
        assertEquals(1, rig.dao.downloads.value.size)
        assertTrue(rig.scheduler.log.isEmpty())
    }

    @Test
    fun `enqueue of a failed download queues it again and clears the error`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.FAILED)))

        rig.controller.enqueue(bookId)

        assertEquals("QUEUED", rig.status())
        assertNull(rig.dao.downloads.value.single().error)
        assertEquals(100L, rig.dao.downloads.value.single().totalBytes)
    }

    @Test
    fun `pause marks the download paused before stopping the work`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.DOWNLOADING)))

        rig.controller.pause(bookId)

        assertEquals("PAUSED", rig.status())
        assertEquals(listOf("cancel $bookId"), rig.scheduler.log)
    }

    @Test
    fun `pause does nothing to a finished download`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.DONE)))

        rig.controller.pause(bookId)

        assertEquals("DONE", rig.status())
        assertTrue(rig.scheduler.log.isEmpty())
    }

    @Test
    fun `resume queues a paused download and schedules it`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.PAUSED)))

        rig.controller.resume(bookId)

        assertEquals("QUEUED", rig.status())
        assertEquals(listOf("schedule $bookId wifiOnly=true replace=false"), rig.scheduler.log)
    }

    @Test
    fun `resume of a finished or missing download does nothing`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.DONE)))

        rig.controller.resume(bookId)
        rig.controller.resume("abs_unknown")

        assertTrue(rig.scheduler.log.isEmpty())
    }

    @Test
    fun `cancel stops the work, waits for it, then deletes the files and rows`() = runTest {
        val dir = bookDirectory(root, "srv1", bookId).apply { mkdirs() }
        File(dir, "11.mp3.part").writeBytes(byteArrayOf(1, 2))
        val other = bookDirectory(root, "srv1", "abs_other").apply { mkdirs() }
        File(other, "1.mp3").writeBytes(byteArrayOf(1))
        val rig = Rig(
            listOf(row(DownloadStatus.DOWNLOADING), row(DownloadStatus.DONE, "abs_other")),
            listOf(DownloadFileEntity(bookId, "11", "a", "p", 10, 2, 0), DownloadFileEntity("abs_other", "1", "b", "q", 1, 1, 0))
        )

        rig.controller.cancel(bookId)

        assertEquals(listOf("cancel $bookId", "idle $bookId"), rig.scheduler.log)
        assertFalse(dir.exists())
        assertTrue(File(other, "1.mp3").exists())
        assertNull(rig.status())
        assertEquals(listOf("abs_other"), rig.dao.files.value.map { it.bookId })
        assertEquals(listOf("abs_other"), rig.dao.downloads.value.map { it.bookId })
    }

    @Test
    fun `remove deletes a finished download's files and rows`() = runTest {
        val dir = bookDirectory(root, "srv1", bookId).apply { mkdirs() }
        File(dir, "11.mp3").writeBytes(byteArrayOf(1))
        val rig = Rig(listOf(row(DownloadStatus.DONE)), listOf(DownloadFileEntity(bookId, "11", "a", "p", 1, 1, 0)))

        rig.controller.remove(bookId)

        assertFalse(dir.exists())
        assertTrue(rig.dao.downloads.value.isEmpty())
        assertTrue(rig.dao.files.value.isEmpty())
        assertEquals(listOf(bookId to true), rig.released, "the player lets go before the files are deleted")
    }

    @Test
    fun `remove deletes the files where the table says they are, after the location changed`() = runTest {
        val oldDir = bookDirectory(File(root, "old"), "srv1", bookId).apply { mkdirs() }
        File(oldDir, "11.mp3").writeBytes(byteArrayOf(1))
        File(oldDir, "cover.jpg").writeBytes(byteArrayOf(2))
        val newRoot = File(root, "new").apply { mkdirs() }
        val rig = Rig(
            listOf(row(DownloadStatus.DONE)),
            listOf(DownloadFileEntity(bookId, "11", "a", File(oldDir, "11.mp3").path, 1, 1, 0)),
            currentRoot = newRoot
        )

        rig.controller.remove(bookId)

        assertFalse(oldDir.exists())
        assertTrue(File(root, "old/downloads/srv1").exists())
        assertTrue(rig.dao.files.value.isEmpty())
    }

    @Test
    fun `remove never deletes a folder that is not the book's own`() = runTest {
        val other = File(root, "other").apply { mkdirs() }
        File(other, "keep.mp3").writeBytes(byteArrayOf(1))
        val rig = Rig(listOf(row(DownloadStatus.DONE)), listOf(DownloadFileEntity(bookId, "11", "a", File(other, "keep.mp3").path, 1, 1, 0)))

        rig.controller.remove(bookId)

        assertTrue(File(other, "keep.mp3").exists())
        assertTrue(rig.dao.downloads.value.isEmpty())
    }

    @Test
    fun `a changed wi-fi setting reschedules waiting and running downloads with it`() = runTest {
        val rig = Rig(
            listOf(
                row(DownloadStatus.QUEUED, "q"), row(DownloadStatus.DOWNLOADING, "d"), row(DownloadStatus.PAUSED, "p"),
                row(DownloadStatus.FAILED, "f"), row(DownloadStatus.DONE, "x")
            )
        )
        rig.settings.wifiOnly.value = false

        rig.controller.reapplyWifiSetting()

        assertEquals(listOf("schedule q wifiOnly=false replace=true", "schedule d wifiOnly=false replace=true"), rig.scheduler.log)
        assertEquals(listOf("QUEUED", "DOWNLOADING", "PAUSED", "FAILED", "DONE"), rig.dao.downloads.value.map { it.status })
    }

    @Test
    fun `two quick wi-fi toggles end with the final setting on every waiting book`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        val slow = object : DownloadScheduler {
            override suspend fun schedule(bookId: String, wifiOnly: Boolean, replace: Boolean) {
                log += "$bookId wifiOnly=$wifiOnly"
                if (log.size == 1) gate.await()
            }
            override suspend fun cancel(bookId: String) {}
        }
        val dao = InMemoryDownloadDao(listOf(row(DownloadStatus.QUEUED, "q"), row(DownloadStatus.DOWNLOADING, "d")))
        val settings = FakeAppSettings()
        val location = object : DownloadLocation {
            override suspend fun rootDir() = root
            override suspend fun freeBytes() = Long.MAX_VALUE
        }
        val controller = DefaultDownloadController(dao, FakeBookDao(book()), settings, location, slow, FakeJobs(mutableListOf()), removal = {})

        settings.wifiOnly.value = false
        val first = launch { controller.reapplyWifiSetting() }
        testScheduler.runCurrent() // the first pass is stuck scheduling "q" with the old value
        settings.wifiOnly.value = true
        val second = launch { controller.reapplyWifiSetting() }
        testScheduler.runCurrent()
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("q wifiOnly=false", "d wifiOnly=false", "q wifiOnly=true", "d wifiOnly=true"), log)
    }

    @Test
    fun `app start schedules queued and running downloads only`() = runTest {
        val rig = Rig(
            listOf(
                row(DownloadStatus.QUEUED, "a"), row(DownloadStatus.DOWNLOADING, "b"),
                row(DownloadStatus.PAUSED, "c"), row(DownloadStatus.FAILED, "d"), row(DownloadStatus.DONE, "e"),
            )
        )

        rig.controller.restoreQueue()

        assertEquals(listOf("schedule a wifiOnly=true replace=false", "schedule b wifiOnly=true replace=false"), rig.scheduler.log)
    }

    @Test
    fun `after the time limit a download the job put back in the queue is scheduled again`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.QUEUED)))

        rig.controller.afterStop(bookId, timedOut = true)

        assertEquals("QUEUED", rig.status())
        assertEquals(listOf("schedule $bookId wifiOnly=true replace=true"), rig.scheduler.log)
    }

    @Test
    fun `a paused download is not scheduled again after a stop`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.PAUSED)))

        rig.controller.afterStop(bookId, timedOut = true)

        assertEquals("PAUSED", rig.status())
        assertTrue(rig.scheduler.log.isEmpty())
    }

    @Test
    fun `a stop that was not the time limit changes nothing, so a newer job is never touched`() = runTest {
        val rig = Rig(listOf(row(DownloadStatus.DOWNLOADING)))

        rig.controller.afterStop(bookId, timedOut = false)

        assertEquals("DOWNLOADING", rig.status())
        assertTrue(rig.scheduler.log.isEmpty())
    }
}
