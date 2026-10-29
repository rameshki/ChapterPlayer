package com.chapterplayer.domain.plex

import com.chapterplayer.domain.downloads.PlannedFile
import kotlin.test.Test
import kotlin.test.assertEquals

class PlexDownloadsTest {

    private fun track(
        key: String,
        part: String? = "/library/parts/$key/1700000000/file.mp3",
        durationMs: Long = 600_000,
        container: String? = "mp3",
        size: Long? = 1_000,
    ) = PlexTrack(ratingKey = key, title = "Track $key", partKey = part, durationMs = durationMs, container = container, sizeBytes = size)

    @Test
    fun `each track is one file in play order, named by its rating key, with its size, length and type`() {
        val plan = plexDownloadPlan(listOf(track("12", size = 5_000, durationMs = 900_000), track("11", container = "mp4")))

        assertEquals(
            PlexDownloadPlan.Files(
                listOf(
                    PlexDownloadFile(PlannedFile("12", "12.mp3", 5_000, 0, 900_000, "audio/mpeg"), "/library/parts/12/1700000000/file.mp3"),
                    PlexDownloadFile(PlannedFile("11", "11.mp4", 1_000, 1, 600_000, "audio/mp4"), "/library/parts/11/1700000000/file.mp3"),
                )
            ),
            plan
        )
    }

    @Test
    fun `a track without a file makes the book not downloadable`() {
        assertEquals(PlexDownloadPlan.Unavailable(PLEX_AUDIO_MISSING_TEXT), plexDownloadPlan(listOf(track("1"), track("2", part = null))))
    }

    @Test
    fun `an unknown size is planned as 0 and an unknown length as 0`() {
        val files = (plexDownloadPlan(listOf(track("1", size = null, durationMs = 0), track("2", size = -4))) as PlexDownloadPlan.Files).files
        assertEquals(listOf(0L, 0L), files.map { it.file.totalBytes })
        assertEquals(listOf(0L, 600_000L), files.map { it.file.durationMs })
    }

    @Test
    fun `a repeated rating key is planned once`() {
        val files = (plexDownloadPlan(listOf(track("1"), track("1"), track("2"))) as PlexDownloadPlan.Files).files
        assertEquals(listOf("1", "2"), files.map { it.file.ino })
        assertEquals(listOf(0, 1), files.map { it.file.sortOrder })
    }

    @Test
    fun `no tracks plan no files`() {
        assertEquals(PlexDownloadPlan.Files(emptyList()), plexDownloadPlan(emptyList()))
    }

    @Test
    fun `the extension comes from the container, else audio`() {
        assertEquals("mp3", plexFileExtension("MP3"))
        assertEquals("m4b", plexFileExtension(" m4b "))
        assertEquals("audio", plexFileExtension(null))
        assertEquals("audio", plexFileExtension(""))
        assertEquals("audio", plexFileExtension("../x"))
        assertEquals("audio", plexFileExtension("averyverylongname"))
    }

    @Test
    fun `an unsafe rating key still gives a name inside the folder`() {
        val files = (plexDownloadPlan(listOf(track("../../x"))) as PlexDownloadPlan.Files).files
        assertEquals("x.mp3", files.single().file.fileName)
    }

    @Test
    fun `the size Plex sends with a range or a whole answer`() {
        assertEquals(9_600_000L, plexSizeFromAnswer(206, contentRange = "bytes 0-0/9600000", contentLength = "1"))
        assertEquals(9_600_000L, plexSizeFromAnswer(200, contentRange = null, contentLength = "9600000"))
        assertEquals(null, plexSizeFromAnswer(206, contentRange = "bytes 0-0/*", contentLength = "1"))
        assertEquals(null, plexSizeFromAnswer(200, contentRange = null, contentLength = null))
        assertEquals(null, plexSizeFromAnswer(200, contentRange = null, contentLength = "0"))
        assertEquals(null, plexSizeFromAnswer(404, contentRange = null, contentLength = "12"))
    }
}
