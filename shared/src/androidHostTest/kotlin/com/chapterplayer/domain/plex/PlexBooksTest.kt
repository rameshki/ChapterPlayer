package com.chapterplayer.domain.plex

import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.plex.PlexParsers
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto
import kotlin.test.Test
import kotlin.test.assertEquals

class PlexBooksTest {

    private fun albums() = PlexParsers.libraryItems(fixture("plex/library-albums.json")).mediaContainer.metadata.orEmpty()

    @Test
    fun `an album from the fixture becomes a book with its author and cover`() {
        val book = plexAlbumToBook(albums()[2], "http://plex.test:32400/", "srv", "4")

        assertEquals("plex_1019", book.id)
        assertEquals("Fixture title 10", book.title)
        assertEquals("Fixture parentTitle 2", book.author)
        assertEquals("http://plex.test:32400/library/metadata/1019/thumb/1700000000", book.coverUrl)
        assertEquals("http://plex.test:32400/library/metadata/1019/children", book.streamUrl)
    }

    @Test
    fun `a blank title or author reads as unknown`() {
        val book = plexAlbumToBook(PlexMetadataDto(ratingKey = "1", title = " ", author = ""), "http://p", "srv", "4")

        assertEquals("Unknown Title", book.title)
        assertEquals("Unknown Author", book.author)
    }

    @Test
    fun `entry kinds`() {
        assertEquals(PlexEntryKind.ALBUM, plexEntryKind(PlexMetadataDto(ratingKey = "1", type = "album")))
        assertEquals(PlexEntryKind.ALBUM, plexEntryKind(PlexMetadataDto(ratingKey = "1")))
        assertEquals(PlexEntryKind.ARTIST, plexEntryKind(PlexMetadataDto(ratingKey = "1", type = "artist")))
        assertEquals(PlexEntryKind.OTHER, plexEntryKind(PlexMetadataDto(ratingKey = "1", type = "track")))
    }

    @Test
    fun `addresses join the base and the path with one slash`() {
        assertEquals("http://p:1/a/b", plexUrl("http://p:1/", "/a/b"))
        assertEquals("http://p:1/a/b", plexUrl("http://p:1", "a/b"))
    }

    @Test
    fun `the rating key is the id without the prefix`() {
        assertEquals("1019", plexRatingKey("plex_1019"))
    }
}
