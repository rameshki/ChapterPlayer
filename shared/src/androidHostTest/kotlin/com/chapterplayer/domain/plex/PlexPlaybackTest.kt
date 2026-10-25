package com.chapterplayer.domain.plex

import com.chapterplayer.data.playback.PlaybackTrack
import com.chapterplayer.domain.playback.ServerProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexPlaybackTest {

    private fun track(
        key: String,
        durationMs: Long,
        part: String? = "/library/parts/$key/1700000000/file.mp3",
        container: String? = "mp3",
        viewOffsetMs: Long? = null,
        viewCount: Int = 0,
        lastViewedAtSec: Long? = null,
    ) = PlexTrack(key, "Track $key", part, durationMs, container, null, viewOffsetMs, viewCount, lastViewedAtSec)

    private val three = listOf(track("1", 600_000), track("2", 900_000), track("3", 300_000))

    // -- Items -------------------------------------------------------------------------------

    @Test
    fun `one item per track on one timeline, addresses under the server and without a token`() {
        val items = plexPlaybackTracks("http://plex.test:32400/", three)!!

        assertEquals(
            listOf(
                PlaybackTrack(0, 0L, 600_000L, "audio/mpeg", "http://plex.test:32400/library/parts/1/1700000000/file.mp3"),
                PlaybackTrack(1, 600_000L, 900_000L, "audio/mpeg", "http://plex.test:32400/library/parts/2/1700000000/file.mp3"),
                PlaybackTrack(2, 1_500_000L, 300_000L, "audio/mpeg", "http://plex.test:32400/library/parts/3/1700000000/file.mp3"),
            ),
            items,
        )
        assertTrue(items.none { "token" in it.url.lowercase() })
    }

    @Test
    fun `a track without a file means the book cannot be played`() {
        assertNull(plexPlaybackTracks("http://plex.test", listOf(track("1", 1_000), track("2", 1_000, part = null))))
    }

    @Test
    fun `the media type follows the container, unknown is left to the player`() {
        assertEquals("audio/mp4", plexMimeType("mp4"))
        assertEquals("audio/mp4", plexMimeType("M4B"))
        assertEquals("audio/mp4", plexMimeType("m4a"))
        assertEquals("audio/mpeg", plexMimeType("mp3"))
        assertEquals("audio/flac", plexMimeType("flac"))
        assertEquals("audio/ogg", plexMimeType("ogg"))
        assertEquals("", plexMimeType("wav-ish"))
        assertEquals("", plexMimeType(null))
    }

    // -- Server position ---------------------------------------------------------------------

    @Test
    fun `nothing viewed means no server progress`() {
        assertNull(plexServerProgress(three))
        assertNull(plexServerProgress(emptyList()))
    }

    @Test
    fun `in the middle of track 3 the position is the finished tracks plus its offset`() {
        val tracks = listOf(
            track("1", 600_000, viewCount = 1, lastViewedAtSec = 1_700_000_100),
            track("2", 900_000, viewCount = 1, lastViewedAtSec = 1_700_000_200),
            track("3", 300_000, viewOffsetMs = 120_000, lastViewedAtSec = 1_700_000_300),
        )

        assertEquals(ServerProgress(1_620_000L, 1_800_000L, false, 1_700_000_300_000L), plexServerProgress(tracks))
    }

    @Test
    fun `every track viewed is a finished book`() {
        val tracks = three.mapIndexed { i, t -> t.copy(viewCount = 1, lastViewedAtSec = 1_700_000_000L + i) }

        assertEquals(ServerProgress(1_800_000L, 1_800_000L, true, 1_700_000_002_000L), plexServerProgress(tracks))
    }

    @Test
    fun `finished tracks without one in progress resume at the first track not yet heard`() {
        val tracks = listOf(three[0].copy(viewCount = 1, lastViewedAtSec = 1_700_000_000), three[1], three[2])

        assertEquals(ServerProgress(600_000L, 1_800_000L, false, 1_700_000_000_000L), plexServerProgress(tracks))
    }

    @Test
    fun `of two tracks in progress the one heard last wins`() {
        val tracks = listOf(
            three[0].copy(viewOffsetMs = 10_000, lastViewedAtSec = 1_700_000_500),
            three[1].copy(viewOffsetMs = 20_000, lastViewedAtSec = 1_700_000_100),
            three[2],
        )

        assertEquals(10_000L, plexServerProgress(tracks)!!.positionMs)
    }

    @Test
    fun `no view time means an update time of 0`() {
        val tracks = listOf(three[0].copy(viewOffsetMs = 5_000), three[1], three[2])

        assertEquals(ServerProgress(5_000L, 1_800_000L, false, 0L), plexServerProgress(tracks))
    }
}
