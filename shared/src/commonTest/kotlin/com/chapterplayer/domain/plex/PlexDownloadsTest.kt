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
    fun `each track is one file in play order - named by its rating key - with its size - length and type`() {
        val plan = plexDownloadPlan(
            listOf(
                track("12", size = 5_000, durationMs = 900_000),
                track("11", part = "/library/parts/11/1700000000/file.m4b", container = "mp4"),
            )
        )

        assertEquals(
            PlexDownloadPlan.Files(
                listOf(
                    PlexDownloadFile(PlannedFile("12", "12.mp3", 5_000, 0, 900_000, "audio/mpeg"), "/library/parts/12/1700000000/file.mp3"),
                    PlexDownloadFile(PlannedFile("11", "11.m4b", 1_000, 1, 600_000, "audio/mp4"), "/library/parts/11/1700000000/file.m4b"),
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
    fun `a track without a known length makes the book not downloadable - as it could never play`() {
        assertEquals(PlexDownloadPlan.Unavailable(PLEX_LENGTH_UNKNOWN_TEXT), plexDownloadPlan(listOf(track("1"), track("2", durationMs = 0))))
    }

    @Test
    fun `an unknown size is planned as 0 - to be asked for`() {
        val files = (plexDownloadPlan(listOf(track("1", size = null), track("2", size = -4))) as PlexDownloadPlan.Files).files
        assertEquals(listOf(0L, 0L), files.map { it.file.totalBytes })
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
    fun `the extension comes from the part's file name - else the container - else audio`() {
        assertEquals("m4b", plexFileExtension("/library/parts/1/2/file.m4b", "mp4"))
        assertEquals("mp3", plexFileExtension("/library/parts/1/2/file.MP3?x=1", null))
        assertEquals("mp4", plexFileExtension("/library/parts/1/2/file", "mp4"))
        assertEquals("mp3", plexFileExtension(null, "MP3"))
        assertEquals("m4b", plexFileExtension(null, " m4b "))
        assertEquals("audio", plexFileExtension(null, null))
        assertEquals("audio", plexFileExtension("/library/parts/1/2/file.", ""))
        assertEquals("audio", plexFileExtension(null, "../x"))
        assertEquals("audio", plexFileExtension("/x/file.averyverylongname", "averyverylongname"))
    }

    @Test
    fun `an unsafe rating key still gives a name inside the folder`() {
        val files = (plexDownloadPlan(listOf(track("../../x"))) as PlexDownloadPlan.Files).files
        assertEquals("x.mp3", files.single().file.fileName)
    }

    @Test
    fun `the size Plex sends with a range or a whole answer`() {
        assertEquals(PlexSizeAnswer.Size(9_600_000L), plexSizeAnswer(206, contentRange = "bytes 0-0/9600000", contentLength = "1"))
        assertEquals(PlexSizeAnswer.Size(9_600_000L), plexSizeAnswer(200, contentRange = null, contentLength = "9600000"))
    }

    @Test
    fun `only a definite answer without a size is final - other answers are asked again later`() {
        assertEquals(PlexSizeAnswer.NoSize, plexSizeAnswer(206, contentRange = "bytes 0-0/*", contentLength = "1"))
        assertEquals(PlexSizeAnswer.NoSize, plexSizeAnswer(200, contentRange = null, contentLength = null))
        assertEquals(PlexSizeAnswer.NoSize, plexSizeAnswer(200, contentRange = null, contentLength = "0"))
        assertEquals(PlexSizeAnswer.Missing, plexSizeAnswer(404, contentRange = null, contentLength = "12"))
        for (status in listOf(301, 302, 307, 403, 408, 429, 500, 503)) {
            assertEquals(PlexSizeAnswer.TryLater, plexSizeAnswer(status, contentRange = null, contentLength = "12"), "status $status")
        }
    }
}
