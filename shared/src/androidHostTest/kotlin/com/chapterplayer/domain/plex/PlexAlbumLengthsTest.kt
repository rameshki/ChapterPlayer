package com.chapterplayer.domain.plex

import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.plex.PlexParsers
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto
import kotlin.test.Test
import kotlin.test.assertEquals

class PlexAlbumLengthsTest {

    private fun track(key: String, album: String?, duration: Long?) = PlexMetadataDto(ratingKey = key, parentRatingKey = album, durationMs = duration)

    @Test
    fun `a section's tracks give each album the sum of its tracks`() {
        val tracks = PlexParsers.libraryItems(fixture("plex/section-tracks.json")).mediaContainer.metadata.orEmpty()
        // Album 2001: 30 + 20 minutes; album 2002 from its media length; album 2003 has a track without any length.
        assertEquals(mapOf("2001" to 3_000_000L, "2002" to 600_000L), albumLengths(tracks))
    }

    @Test
    fun `an album with a track of unknown length gets no length at all`() {
        val lengths = albumLengths(listOf(track("1", "a", 1_000L), track("2", "a", null), track("3", "b", 5L)))
        assertEquals(mapOf("b" to 5L), lengths)
    }

    @Test
    fun `tracks without an album are ignored`() {
        assertEquals(mapOf("a" to 7L), albumLengths(listOf(track("1", null, 99L), track("2", "a", 7L))))
    }
}
