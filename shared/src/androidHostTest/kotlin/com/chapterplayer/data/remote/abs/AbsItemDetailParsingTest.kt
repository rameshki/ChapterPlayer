package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.SeriesRef
import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.dto.AbsItemDetailDto
import com.chapterplayer.data.remote.abs.dto.toBookDetails
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
}
