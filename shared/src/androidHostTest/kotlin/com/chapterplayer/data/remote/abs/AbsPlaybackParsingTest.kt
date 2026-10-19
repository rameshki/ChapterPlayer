package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.remote.abs.dto.AbsPlaySessionDto
import com.chapterplayer.data.remote.abs.dto.AbsPlayTrackDto
import com.chapterplayer.data.remote.abs.dto.toPlaybackSession
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The play-session response, from the synthetic fixture. */
class AbsPlaybackParsingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val dto = json.decodeFromString<AbsPlaySessionDto>(fixture("abs/play-session.json"))

    @Test
    fun `seconds become exact milliseconds`() {
        val session = dto.toPlaybackSession("srv1", "abs_li_fixture01", "http://abs.test")

        assertEquals("play_fixture01", session.sessionId)
        assertEquals("srv1", session.serverId)
        assertEquals("abs_li_fixture01", session.bookId)
        assertEquals(5_400_500L, session.durationMs)
        assertEquals(1_735_250L, session.startPositionMs)
    }

    @Test
    fun `tracks are ordered by index with their offsets`() {
        val tracks = dto.toPlaybackSession("s", "b", "http://abs.test").tracks

        assertEquals(listOf(1, 2), tracks.map { it.index })
        assertEquals(listOf(0L, 3_000_125L), tracks.map { it.startMs })
        assertEquals(listOf(3_000_125L, 2_400_375L), tracks.map { it.durationMs })
        assertEquals("audio/mp4", tracks[0].mimeType)
    }

    @Test
    fun `chapters are mapped to milliseconds`() {
        val chapters = dto.toPlaybackSession("s", "b", "http://abs.test").chapters

        assertEquals(
            listOf(Chapter(0, "Chapter One", 0, 1_735_250), Chapter(1, "Chapter Two", 1_735_250, 5_400_500)),
            chapters
        )
    }

    private fun urlFor(base: String, contentUrl: String) =
        AbsPlaySessionDto("x", 1.0, audioTracks = listOf(AbsPlayTrackDto(0, 0.0, 1.0, "audio/mp4", contentUrl)))
            .toPlaybackSession("s", "b", base).tracks.single().url

    @Test
    fun `urls are absolute whatever the slashes`() {
        val expected = "http://abs.test/api/items/i/file/1"
        assertEquals(expected, urlFor("http://abs.test", "/api/items/i/file/1"))
        assertEquals(expected, urlFor("http://abs.test/", "/api/items/i/file/1"))
        assertEquals(expected, urlFor("http://abs.test", "api/items/i/file/1"))
        assertEquals(expected, urlFor("http://abs.test/", "api/items/i/file/1"))
    }

    @Test
    fun `missing optional fields default`() {
        val minimal = json.decodeFromString<AbsPlaySessionDto>("""{"id":"p","duration":10}""")
            .toPlaybackSession("s", "b", "http://abs.test")

        assertEquals(0L, minimal.startPositionMs)
        assertEquals(emptyList(), minimal.tracks)
        assertEquals(emptyList(), minimal.chapters)
        val track = json.decodeFromString<AbsPlayTrackDto>("""{"index":0,"startOffset":0,"duration":1,"contentUrl":"/a"}""")
        assertEquals("", track.mimeType)
    }
}
