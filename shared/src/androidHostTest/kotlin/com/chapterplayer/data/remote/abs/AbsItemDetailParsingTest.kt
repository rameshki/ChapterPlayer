package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.SeriesRef
import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.dto.AbsItemDetailDto
import com.chapterplayer.data.remote.abs.dto.toAudioFileEntries
import com.chapterplayer.data.remote.abs.dto.toBookDetails
import com.chapterplayer.domain.downloads.AudioFileEntry
import com.chapterplayer.domain.downloads.PlannedFile
import com.chapterplayer.domain.downloads.planFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AbsItemDetailParsingTest {

    private fun parse(path: String): AbsItemDetailDto = ApiJson.decodeFromString(fixture(path))

    @Test
    fun `chapters convert seconds to milliseconds exactly`() {
        val details = parse("abs/item-detail.json").toBookDetails()
        assertEquals(
            listOf(
                Chapter(0, "Opening", 0, 1_800_250),
                Chapter(1, "Middle", 1_800_250, 3_600_000),
                Chapter(2, "Ending", 3_600_000, 5_400_500)
            ),
            details.chapters
        )
    }

    @Test
    fun `description prefers the plain text, genres year and series come through`() {
        val details = parse("abs/item-detail.json").toBookDetails()
        assertEquals("An invented story.", details.description)
        assertEquals(listOf("Fantasy", "Adventure"), details.genres)
        assertEquals("2019", details.publishedYear)
        assertEquals(listOf(SeriesRef("Fixture Saga", "2")), details.series)
    }

    @Test
    fun `a blank plain description falls back to the html one with tags and entities removed`() {
        val details = parse("abs/item-detail-minimal.json").toBookDetails()
        assertEquals("Only html here & more.", details.description)
    }

    @Test
    fun `missing optional fields give empty lists and nulls`() {
        val details = parse("abs/item-detail-minimal.json").toBookDetails()
        assertEquals(emptyList(), details.chapters)
        assertEquals(emptyList(), details.genres)
        assertEquals(emptyList(), details.series)
        assertNull(details.publishedYear)
    }

    @Test
    fun `no description at all gives null`() {
        val json = """{"id":"x","media":{"metadata":{"title":"T"}}}"""
        assertNull(ApiJson.decodeFromString<AbsItemDetailDto>(json).toBookDetails().description)
    }

    @Test
    fun `a series without a sequence keeps it null and a blank series name is dropped`() {
        val json = """{"id":"x","media":{"metadata":{"series":[{"id":"a","name":"Saga"},{"id":"b","name":" ","sequence":"1"}]}}}"""
        assertEquals(listOf(SeriesRef("Saga", null)), ApiJson.decodeFromString<AbsItemDetailDto>(json).toBookDetails().series)
    }

    @Test
    fun `a missing description does not drop the chapters, genres or series`() {
        val json = """{"id":"x","media":{"metadata":{"genres":["G"],"series":[{"name":"Saga","sequence":"1"}]},"chapters":[{"id":0,"start":0,"end":2,"title":"One"}]}}"""
        val details = ApiJson.decodeFromString<AbsItemDetailDto>(json).toBookDetails()
        assertNull(details.description)
        assertEquals(listOf(Chapter(0, "One", 0, 2_000)), details.chapters)
        assertEquals(listOf("G"), details.genres)
        assertEquals(listOf(SeriesRef("Saga", "1")), details.series)
    }

    @Test
    fun `entities are decoded once and a non breaking space becomes a space`() {
        val json = """{"id":"x","media":{"metadata":{"description":"A&nbsp;B &amp;lt; C"}}}"""
        assertEquals("A B &lt; C", ApiJson.decodeFromString<AbsItemDetailDto>(json).toBookDetails().description)
    }

    @Test
    fun `a chapter without a title still parses with an empty title`() {
        val json = """{"id":"x","media":{"chapters":[{"id":0,"start":0,"end":1}]}}"""
        assertEquals(listOf(Chapter(0, "", 0, 1_000)), ApiJson.decodeFromString<AbsItemDetailDto>(json).toBookDetails().chapters)
    }

    @Test
    fun `audio files come through with ino, name, size, index and the exclude flag`() {
        val entries = parse("abs/item-detail-files.json").toAudioFileEntries()
        assertEquals(
            listOf(
                AudioFileEntry("100000000000000012", "Fixture Multi - 02.mp3", 2_048_000, 2, excluded = false),
                AudioFileEntry("100000000000000011", "Fixture Multi - 01.mp3", 1_024_000, 1, excluded = false),
                AudioFileEntry("100000000000000013", "Fixture Multi - bonus.mp3", 512_000, 3, excluded = true),
            ),
            entries
        )
    }

    @Test
    fun `the recorded item plans its files in index order without the excluded one`() {
        val planned = planFiles(parse("abs/item-detail-files.json").toAudioFileEntries())
        assertEquals(
            listOf(
                PlannedFile("100000000000000011", "Fixture Multi - 01.mp3", 1_024_000, 0),
                PlannedFile("100000000000000012", "Fixture Multi - 02.mp3", 2_048_000, 1),
            ),
            planned
        )
    }

    @Test
    fun `an audio file without metadata or size still parses, and is not planned`() {
        val json = """{"id":"x","media":{"audioFiles":[{"ino":"7"},{"index":2,"metadata":{"filename":"a.mp3"}}]}}"""
        val entries = ApiJson.decodeFromString<AbsItemDetailDto>(json).toAudioFileEntries()
        assertEquals(listOf(AudioFileEntry("7", null, null, null), AudioFileEntry(null, "a.mp3", null, 2)), entries)
        assertEquals(emptyList(), planFiles(entries))
    }

    @Test
    fun `an item without audio files gives no entries`() {
        assertEquals(emptyList(), parse("abs/item-detail-minimal.json").toAudioFileEntries())
    }
}
