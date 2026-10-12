package com.chapterplayer.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Chapters are stored in Room as JSON text, so the format must stay readable by every
 * version of the app. These tests pin the format the app has been writing.
 */
class ChapterJsonTest {

    private val chapters = listOf(
        Chapter(id = 0, title = "Intro", startMs = 0, endMs = 1_000),
        Chapter(id = 1, title = "Two: \"quoted\" é – café", startMs = 1_000, endMs = 2_500)
    )

    // Exactly what the app stores today for the chapters above.
    private val stored =
        """[{"id":0,"title":"Intro","startMs":0,"endMs":1000},""" +
            """{"id":1,"title":"Two: \"quoted\" é – café","startMs":1000,"endMs":2500}]"""
                .replace("\u00e9", "é").replace("\u2013", "–")

    @Test
    fun `writes the format the app has always stored`() {
        assertEquals(stored, ChapterJson.encode(chapters))
    }

    @Test
    fun `reads a row written by an earlier version`() {
        assertEquals(chapters, ChapterJson.decode(stored))
    }

    @Test
    fun `an empty list round-trips`() {
        assertEquals("[]", ChapterJson.encode(emptyList()))
        assertEquals(emptyList(), ChapterJson.decode("[]"))
    }

    @Test
    fun `the derived duration is not stored`() {
        assertEquals(false, ChapterJson.encode(chapters).contains("durationMs"))
    }

    @Test
    fun `ignores fields it does not know`() {
        val json = """[{"id":7,"title":"A","startMs":0,"endMs":5,"added":"later"}]"""

        assertEquals(listOf(Chapter(7, "A", 0, 5)), ChapterJson.decode(json))
    }

    @Test
    fun `a json null reads as no chapters`() {
        assertEquals(emptyList(), ChapterJson.decode("null"))
    }

    @Test
    fun `rejects text that is not json`() {
        assertFails { ChapterJson.decode("not json") }
    }

    @Test
    fun `rejects a chapter that is missing a field`() {
        assertFails { ChapterJson.decode("""[{"id":1,"title":"A","startMs":0}]""") }
    }
}
