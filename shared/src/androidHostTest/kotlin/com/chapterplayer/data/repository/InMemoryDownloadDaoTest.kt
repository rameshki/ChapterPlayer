package com.chapterplayer.data.repository

import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the fake to the contract the real DownloadDao queries are written for. */
class InMemoryDownloadDaoTest {
    private fun download(id: String, status: String = "QUEUED") = DownloadEntity(id, "s1", status, 300, null, 1)
    private fun file(book: String, ino: String, order: Int, done: Long = 0) =
        DownloadFileEntity(book, ino, "$ino.mp3", "/p/$ino.mp3", 100, done, order)

    @Test
    fun `upsert replaces by book id`() = runTest {
        val dao = InMemoryDownloadDao()
        dao.upsert(download("b1"))
        dao.upsert(download("b1", "DONE"))
        assertEquals("DONE", dao.get("b1")?.status)
        assertEquals(1, dao.downloads.value.size)
    }

    @Test
    fun `files come back in sort order and replace by book and ino`() = runTest {
        val dao = InMemoryDownloadDao()
        dao.upsertFiles(listOf(file("b1", "f2", 1), file("b1", "f1", 0), file("b2", "f1", 0)))
        dao.upsertFiles(listOf(file("b1", "f2", 1, done = 50)))
        assertEquals(listOf("f1", "f2"), dao.getFiles("b1").map { it.ino })
        assertEquals(50L, dao.getFiles("b1")[1].doneBytes)
        assertEquals(3, dao.files.value.size)
    }

    @Test
    fun `status and file progress update only the matching row`() = runTest {
        val dao = InMemoryDownloadDao(listOf(download("b1"), download("b2")), listOf(file("b1", "f1", 0), file("b1", "f2", 1)))
        dao.updateStatus("b1", "FAILED", "no space", 9)
        dao.updateFileProgress("b1", "f2", 70)
        assertEquals(DownloadEntity("b1", "s1", "FAILED", 300, "no space", 9), dao.get("b1"))
        assertEquals("QUEUED", dao.get("b2")?.status)
        assertEquals(listOf(0L, 70L), dao.getFiles("b1").map { it.doneBytes })
    }

    @Test
    fun `delete book removes the download and its files only`() = runTest {
        val dao = InMemoryDownloadDao(listOf(download("b1"), download("b2")), listOf(file("b1", "f1", 0), file("b2", "f1", 0)))
        dao.deleteBook("b1")
        assertNull(dao.get("b1"))
        assertEquals(emptyList(), dao.getFiles("b1"))
        assertEquals(1, dao.getFiles("b2").size)
    }
}
