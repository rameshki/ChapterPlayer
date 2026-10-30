package com.chapterplayer.domain.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadPlanTest {

    @Test
    fun `files are sorted by index and numbered from zero`() {
        val planned = planFiles(
            listOf(
                AudioFileEntry(ino = "30", fileName = "Part 3.mp3", sizeBytes = 300, index = 3),
                AudioFileEntry(ino = "10", fileName = "Part 1.mp3", sizeBytes = 100, index = 1),
                AudioFileEntry(ino = "20", fileName = "Part 2.mp3", sizeBytes = 200, index = 2),
            )
        )
        assertEquals(
            listOf(
                PlannedFile("10", "Part 1.mp3", 100, 0),
                PlannedFile("20", "Part 2.mp3", 200, 1),
                PlannedFile("30", "Part 3.mp3", 300, 2),
            ),
            planned
        )
    }

    @Test
    fun `a file's length and type are kept - and unknown or negative ones become 0 and empty`() {
        val planned = planFiles(
            listOf(
                AudioFileEntry(ino = "1", fileName = "a.mp3", sizeBytes = 10, index = 1, durationMs = 5_000, mimeType = " audio/mpeg "),
                AudioFileEntry(ino = "2", fileName = "b.mp3", sizeBytes = 10, index = 2),
                AudioFileEntry(ino = "3", fileName = "c.mp3", sizeBytes = 10, index = 3, durationMs = -4),
            )
        )
        assertEquals(listOf(5_000L, 0L, 0L), planned.map { it.durationMs })
        assertEquals(listOf("audio/mpeg", "", ""), planned.map { it.mimeType })
    }

    @Test
    fun `entries without an ino - without a size or with a size of zero are dropped`() {
        val planned = planFiles(
            listOf(
                AudioFileEntry(ino = null, fileName = "a.mp3", sizeBytes = 10, index = 1),
                AudioFileEntry(ino = " ", fileName = "b.mp3", sizeBytes = 10, index = 2),
                AudioFileEntry(ino = "3", fileName = "c.mp3", sizeBytes = null, index = 3),
                AudioFileEntry(ino = "4", fileName = "d.mp3", sizeBytes = 0, index = 4),
                AudioFileEntry(ino = "5", fileName = "e.mp3", sizeBytes = -1, index = 5),
                AudioFileEntry(ino = "6", fileName = "f.mp3", sizeBytes = 60, index = 6),
            )
        )
        assertEquals(listOf(PlannedFile("6", "f.mp3", 60, 0)), planned)
    }

    @Test
    fun `excluded files and repeated inos are dropped`() {
        val planned = planFiles(
            listOf(
                AudioFileEntry(ino = "1", fileName = "a.mp3", sizeBytes = 10, index = 1),
                AudioFileEntry(ino = "2", fileName = "b.mp3", sizeBytes = 10, index = 2, excluded = true),
                AudioFileEntry(ino = "1", fileName = "a again.mp3", sizeBytes = 10, index = 3),
            )
        )
        assertEquals(listOf("1"), planned.map { it.ino })
    }

    @Test
    fun `entries without an index keep their order after the indexed ones`() {
        val planned = planFiles(
            listOf(
                AudioFileEntry(ino = "x", fileName = "x.mp3", sizeBytes = 1, index = null),
                AudioFileEntry(ino = "y", fileName = "y.mp3", sizeBytes = 1, index = null),
                AudioFileEntry(ino = "z", fileName = "z.mp3", sizeBytes = 1, index = 1),
            )
        )
        assertEquals(listOf("z", "x", "y"), planned.map { it.ino })
    }

    @Test
    fun `a missing or unsafe server name is replaced by a safe one`() {
        val planned = planFiles(
            listOf(
                AudioFileEntry(ino = "1", fileName = null, sizeBytes = 1, index = 1),
                AudioFileEntry(ino = "2", fileName = "../../etc/passwd", sizeBytes = 1, index = 2),
            )
        )
        assertEquals("1", planned[0].fileName)
        assertEquals("passwd", planned[1].fileName)
    }

    @Test
    fun `safe file names never hold a path separator or a parent step`() {
        for (name in listOf("../x.mp3", "..\\..\\x.mp3", "a/b/c.mp3", "a\\b.mp3", "..", ".", "/", "....//x", "C:\\x.mp3", "a\u0000b.mp3")) {
            val safe = safeFileName(name)
            assertFalse('/' in safe, "slash in $safe")
            assertFalse('\\' in safe, "backslash in $safe")
            assertFalse(':' in safe, "colon in $safe")
            assertFalse(".." in safe, "parent step in $safe")
            assertFalse(safe.startsWith("."), "hidden name $safe")
            assertFalse(safe.any { it.code < 32 }, "control character in $safe")
            assertTrue(safe.isNotEmpty())
        }
        assertEquals("c.mp3", safeFileName("a/b/c.mp3"))
        assertEquals("Chapter 01.m4b", safeFileName("Chapter 01.m4b"))
        assertEquals("file", safeFileName(".."))
    }

    @Test
    fun `the extension is the lower case letters after the last dot - or bin`() {
        assertEquals("mp3", fileExtension("Part 1.MP3"))
        assertEquals("m4b", fileExtension("book.final.m4b"))
        assertEquals("bin", fileExtension("no extension"))
        assertEquals("bin", fileExtension(null))
        assertEquals("bin", fileExtension("weird.m p3"))
        assertEquals("bin", fileExtension("toolong.abcdefghij"))
        assertEquals("bin", fileExtension("../x./"))
    }

    @Test
    fun `final and part names are the ino and the extension - made safe`() {
        assertEquals("42.mp3", finalFileName("42", "mp3"))
        assertEquals("42.mp3.part", partFileName("42", "mp3"))
        assertEquals("evil.mp3", finalFileName("../../evil", "mp3"))
        assertFalse('/' in partFileName("a/../b", "m4b"))
    }
}
