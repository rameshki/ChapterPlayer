package com.chapterplayer.domain.plex

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.plex.PlexParsers
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How a Plex album's tracks and track details become one book's audio and chapters (recorded-shape fixtures). */
class PlexBookContentTest {

    private fun entries(name: String) = PlexParsers.libraryItems(fixture("plex/$name")).mediaContainer.metadata.orEmpty()

    private fun detail(name: String) = entries(name).single().let { it.ratingKey to it }

    @Test
    fun `a single-file book takes its chapters from the track detail`() {
        val content = plexBookContent(entries("album-tracks.json"), mapOf(detail("track-with-chapters.json")))

        val track = content.tracks.single()
        assertEquals(PlexTrack("1024", "Fixture title 12", "/library/parts/1027/1700000000/file.m4b", 47_198_808L, "mp4", 749_847_734L), track)
        assertEquals(24, content.chapters.size)
        assertEquals(Chapter(0, "Fixture tag 6", 0L, 43_560L), content.chapters.first())
        assertEquals(Chapter(23, "Fixture tag 29", 47_121_400L, 47_198_800L), content.chapters.last())
        assertEquals((0 until 24).toList(), content.chapters.map { it.id })
        assertEquals(47_198_808L, content.durationMs)
        assertEquals(749_847_734L, content.sizeBytes)
    }

    @Test
    fun `a single-file book without its detail is one chapter`() {
        val content = plexBookContent(entries("album-tracks.json"), emptyMap())

        assertEquals(listOf(Chapter(0, "Fixture title 12", 0L, 47_198_808L)), content.chapters)
    }

    @Test
    fun `a multi-track book moves each track's chapters by the tracks before it`() {
        val details = mapOf(
            detail("track-1102-without-chapters.json"),
            detail("track-1103-with-chapters.json"),
            detail("track-1104-no-duration.json"),
        )

        val content = plexBookContent(entries("album-tracks-multi.json"), details)

        assertEquals(listOf("1102", "1103", "1104"), content.tracks.map { it.ratingKey })
        assertEquals(
            listOf(
                "/library/parts/1202/1700000000/file.mp3",
                "/library/parts/1203/1700000000/file.mp3",
                "/library/parts/1204/1700000000/file.mp3",
            ),
            content.tracks.map { it.partKey }
        )
        assertEquals(listOf("mp3", "mp3", "mp3"), content.tracks.map { it.container })
        // The third track states no length anywhere: its last chapter's end is its length.
        assertEquals(listOf(600_000L, 900_000L, 120_000L), content.tracks.map { it.durationMs })
        assertEquals(
            listOf(
                Chapter(0, "Fixture title 13", 0L, 600_000L),               // no chapters: the track is the chapter
                Chapter(1, "Fixture tag 30", 600_000L, 900_000L),
                Chapter(2, "Fixture tag 31", 900_000L, 1_500_000L),
                Chapter(3, "Fixture tag 32", 1_500_000L, 1_620_000L),
            ),
            content.chapters
        )
        assertEquals(1_620_000L, content.durationMs)
    }

    @Test
    fun `tracks without details are one chapter each`() {
        val content = plexBookContent(entries("album-tracks-multi.json"), emptyMap())

        assertEquals(
            listOf(
                Chapter(0, "Fixture title 13", 0L, 600_000L),
                Chapter(1, "Fixture title 14", 600_000L, 1_500_000L),
                Chapter(2, "Fixture title 15", 1_500_000L, 1_500_000L), // no length known at all: 0 long
            ),
            content.chapters
        )
        assertEquals(0L, content.tracks.last().durationMs)
    }

    @Test
    fun `a chapter without an end runs to the next one, the last to the track's end`() {
        val track = PlexParsers.libraryItems(
            """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"7","type":"track","title":"T","duration":1000,
                "Chapter":[{"id":1,"tag":"B","index":2,"startTimeOffset":400},{"id":2,"tag":"A","index":1,"startTimeOffset":0}]}]}}"""
        ).mediaContainer.metadata!!.single()

        val content = plexBookContent(listOf(track), emptyMap())

        // Sorted by start, whatever order the server sent.
        assertEquals(listOf(Chapter(0, "A", 0, 400), Chapter(1, "B", 400, 1000)), content.chapters)
    }

    @Test
    fun `blank names are filled in`() {
        val listed = listOf(PlexMetadataDto(ratingKey = "1", type = "track", title = "", durationMs = 10))
        val withBlankChapter = PlexParsers.libraryItems(
            """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"1","type":"track","Chapter":[{"tag":"","startTimeOffset":0,"endTimeOffset":10}]}]}}"""
        ).mediaContainer.metadata!!.single()

        assertEquals("Track 1", plexBookContent(listed, emptyMap()).chapters.single().title)
        assertEquals("Chapter 1", plexBookContent(listed, mapOf("1" to withBlankChapter)).chapters.single().title)
    }

    @Test
    fun `entries that are not tracks are left out`() {
        val listed = listOf(PlexMetadataDto(ratingKey = "1", type = "photo"), PlexMetadataDto(ratingKey = "2", type = "track", title = "T"))

        assertEquals(listOf("2"), plexBookContent(listed, emptyMap()).tracks.map { it.ratingKey })
    }

    @Test
    fun `an unknown size or file is null`() {
        val content = plexBookContent(listOf(PlexMetadataDto(ratingKey = "1", type = "track", title = "T")), emptyMap())

        assertNull(content.tracks.single().partKey)
        assertNull(content.sizeBytes)
        assertNull(PlexBookContent(emptyList(), emptyList()).sizeBytes)
    }
}
