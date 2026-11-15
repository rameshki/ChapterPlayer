package com.chapterplayer.data.diagnostics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemReporterTest {

    @AfterTest
    fun tearDown() = ProblemLog.clear()

    @Test
    fun `the log keeps the newest lines and redacts what it is given`() {
        repeat(400) { ProblemLog.add("line $it") }
        assertEquals(300, ProblemLog.snapshot().size)
        assertEquals("line 399", ProblemLog.snapshot().last())
        ProblemLog.add("GET https://books.example.org/api failed password=hunter2")
        val last = ProblemLog.snapshot().last()
        assertFalse("books.example.org" in last)
        assertFalse("hunter2" in last)
    }

    @Test
    fun `the log is safe to write from several threads`() {
        val threads = (1..4).map { t -> Thread { repeat(500) { ProblemLog.add("t$t $it") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(300, ProblemLog.snapshot().size)
    }

    @Test
    fun `a failed sync is worded without the server's own text`() {
        val line = syncLine(com.chapterplayer.data.sync.ServerSyncPhase.Failed("Library My Private Books: timeout", 1L))
        assertEquals("last sync failed", line)
        assertEquals("never synced", syncLine(com.chapterplayer.data.sync.ServerSyncPhase.Idle(null, 0)))
        assertEquals("last sync fine, 5 books", syncLine(com.chapterplayer.data.sync.ServerSyncPhase.Idle(1L, 5)))
    }

    @Test
    fun `a real crash saved to the file keeps its frames when it is read back`() {
        val file = java.io.File.createTempFile("crash", ".txt")
        val store = FileCrashStore(file)
        store.save(IllegalStateException("bad token=abc123SECRET").stackTraceToString())
        val back = store.load()!!
        assertTrue("java.lang.IllegalStateException" in back)
        assertTrue("com.chapterplayer.data.diagnostics.ProblemReporterTest" in back)
        assertFalse("abc123SECRET" in back)
        file.delete()
    }

    @Test
    fun `a saved crash is read back redacted and can be cleared`() {
        val file = java.io.File.createTempFile("crash", ".txt")
        val store = FileCrashStore(file)
        store.save("Boom at https://books.example.org/x token=abc123SECRET")
        val back = store.load()!!
        assertTrue("Boom" in back)
        assertFalse("books.example.org" in back)
        assertFalse("abc123SECRET" in back)
        store.clear()
        assertNull(store.load())
    }
}
