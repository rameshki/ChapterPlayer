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

    // -- Reports while playing ----------------------------------------------------------------

    @Test
    fun `the timeline names the track the book position is in and the offset inside it`() {
        assertEquals(PlexTimelinePoint(0, "1", 0L, 600_000L), plexTimelinePoint(three, 0L))
        assertEquals(PlexTimelinePoint(1, "2", 100_000L, 900_000L), plexTimelinePoint(three, 700_000L))
        assertEquals(PlexTimelinePoint(1, "2", 0L, 900_000L), plexTimelinePoint(three, 600_000L))
        assertEquals(PlexTimelinePoint(2, "3", 300_000L, 300_000L), plexTimelinePoint(three, 9_999_999L))
        assertNull(plexTimelinePoint(emptyList(), 0L))
    }

    @Test
    fun `a track is done when playing passed into a later one or is within its last 5 seconds`() {
        // Moved on from track 1 to track 2: track 1 is done.
        assertEquals(listOf(0), plexCompletedTracks(three, previousIndex = 0, point = plexTimelinePoint(three, 610_000L)!!))
        // A jump over track 2: both are done.
        assertEquals(listOf(0, 1), plexCompletedTracks(three, previousIndex = 0, point = plexTimelinePoint(three, 1_510_000L)!!))
        // Near the end of track 1.
        assertEquals(listOf(0), plexCompletedTracks(three, previousIndex = 0, point = plexTimelinePoint(three, 596_000L)!!))
        // Still in the middle, or back in an earlier track: nothing.
        assertEquals(emptyList(), plexCompletedTracks(three, previousIndex = 0, point = plexTimelinePoint(three, 300_000L)!!))
        assertEquals(emptyList(), plexCompletedTracks(three, previousIndex = 2, point = plexTimelinePoint(three, 300_000L)!!))
        // The first report of a session does not finish the tracks before it (they were heard before, or skipped).
        assertEquals(emptyList(), plexCompletedTracks(three, previousIndex = null, point = plexTimelinePoint(three, 1_510_000L)!!))
    }

    @Test
    fun `a track of unknown length is never done by its position`() {
        val tracks = listOf(track("1", 0L), track("2", 1_000L))
        assertEquals(emptyList(), plexCompletedTracks(tracks, previousIndex = 0, point = PlexTimelinePoint(0, "1", 0L, 0L)))
    }

    // -- A position sent without a session (the queue, marks) -----------------------------------

    @Test
    fun `a position finishes the unheard tracks before it, clears the ones from it on and sets the offset`() {
        val tracks = listOf(
            track("1", 600_000),
            track("2", 900_000, viewCount = 1),
            track("3", 300_000, viewCount = 1),
        )

        assertEquals(
            PlexProgressPlan(scrobble = listOf("1"), unscrobble = listOf("2", "3"), point = PlexTimelinePoint(1, "2", 100_000L, 900_000L)),
            plexProgressPlan(tracks, positionMs = 700_000L, isFinished = false),
        )
    }

    @Test
    fun `a position near the end of its track finishes that track too`() {
        assertEquals(
            PlexProgressPlan(scrobble = listOf("1"), unscrobble = emptyList(), point = PlexTimelinePoint(0, "1", 598_000L, 600_000L)),
            plexProgressPlan(three, positionMs = 598_000L, isFinished = false),
        )
    }

    @Test
    fun `finished scrobbles every track, not finished at the start clears them all`() {
        assertEquals(PlexProgressPlan(scrobble = listOf("1", "2", "3"), unscrobble = emptyList(), point = null), plexProgressPlan(three, 5_000L, isFinished = true))
        val heard = three.map { it.copy(viewCount = 1) }
        assertEquals(
            PlexProgressPlan(scrobble = emptyList(), unscrobble = listOf("1", "2", "3"), point = PlexTimelinePoint(0, "1", 0L, 600_000L)),
            plexProgressPlan(heard, positionMs = 0L, isFinished = false),
        )
    }

    @Test
    fun `a book without tracks has nothing to send`() {
        assertEquals(PlexProgressPlan(emptyList(), emptyList(), null), plexProgressPlan(emptyList(), 0L, isFinished = false))
    }
}
