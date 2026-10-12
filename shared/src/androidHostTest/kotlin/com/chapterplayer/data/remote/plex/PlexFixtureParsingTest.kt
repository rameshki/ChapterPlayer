package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.abs.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses responses recorded from a real Plex Media Server 1.43 and plex.tv (names, ids,
 * paths, addresses and tokens replaced with synthetic values; structure and number types kept).
 */
class PlexFixtureParsingTest {

    private fun items(name: String) = PlexParsers.libraryItems(fixture("plex/$name")).mediaContainer.metadata.orEmpty()

    @Test
    fun `a new sign-in code has no token yet`() {
        val pin = PlexParsers.pin(fixture("plex/plextv-pin-created.json"))

        assertEquals(1000001L, pin.id)
        assertEquals("fixturecode000000000000", pin.code)
        assertNull(pin.authToken)
    }

    @Test
    fun `an approved sign-in code carries the token`() {
        val pin = PlexParsers.pin(fixture("plex/plextv-pin-approved.json"))

        assertEquals("fixture-account-token", pin.authToken)
    }

    @Test
    fun `parses the servers on the account with their connections`() {
        val servers = PlexParsers.resources(fixture("plex/plextv-resources.json"))

        assertEquals(listOf("Fixture Server 1", "Fixture Server 2"), servers.map { it.name })
        assertEquals(listOf("server", "server"), servers.map { it.provides })
        assertEquals("fixture-access-token-1", servers[0].accessToken)
        assertEquals(listOf(true, false, false), servers[0].connections.map { it.local })
        assertTrue(servers[0].connections[0].uri.startsWith("https://192-0-2-11."))
    }

    @Test
    fun `parses the library sections`() {
        val sections = PlexParsers.sections(fixture("plex/sections.json")).mediaContainer

        assertEquals(3, sections.size)
        assertEquals(listOf("movie", "show", "artist"), sections.directories?.map { it.type })
        assertEquals("4", sections.directories?.single { it.type == "artist" }?.key)
    }

    @Test
    fun `the library call the app makes today returns artists with no audio details`() {
        // Audiobooks are albums under an artist in a Plex music library. The app asks for the
        // section's top level, so what it gets back are the artists, with no author, duration,
        // media or chapters.
        val artists = items("library-all-artists.json")

        assertEquals(5, artists.size)
        assertEquals("1003", artists[0].ratingKey)
        assertTrue(artists.all { it.author == null })
        assertTrue(artists.all { it.durationMs == null })
        assertTrue(artists.all { it.media == null })
        assertTrue(artists.all { it.chapters == null })
        assertEquals(listOf(false, false, true, false, false), artists.map { it.thumb != null })
    }

    @Test
    fun `albums carry the author as the parent title but no audio`() {
        val albums = items("library-albums.json")

        assertEquals(4, albums.size)
        assertEquals("Fixture parentTitle 1", albums[0].author)
        assertTrue(albums.all { it.durationMs == null && it.media == null })
    }

    @Test
    fun `a track carries the duration and the stream path`() {
        val track = items("album-tracks.json").single()

        assertEquals(47198808L, track.durationMs)
        val part = assertNotNull(track.media?.firstOrNull()?.parts?.firstOrNull())
        assertEquals(1027L, part.id)
        assertEquals("/library/parts/1027/1700000000/file.m4b", part.key)
    }

    @Test
    fun `a track detail carries its chapters`() {
        val chapters = assertNotNull(items("track-with-chapters.json").single().chapters)

        assertEquals(24, chapters.size)
        assertEquals(1029, chapters.first().id)
        assertEquals(1, chapters.first().index)
        assertEquals("Fixture tag 6", chapters.first().title)
        assertEquals(0L, chapters.first().startTimeMs)
        assertEquals("thumb=1029", chapters.first().filter)
        assertEquals(47121400L, chapters.last().startTimeMs)
    }
}
