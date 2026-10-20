package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.playback.TrackSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackMappingTest {

    private val book = AudioBook(
        id = "abs_book1", title = "Sample Book", author = "Sample Author", narrator = "N",
        durationMs = 3_500, chapters = emptyList(), streamUrl = "", coverUrl = "http://x/cover",
        source = ServerSource.ABS, serverId = "s1",
    )

    private fun session(vararg tracks: PlaybackTrack) = PlaybackSession(
        sessionId = "sess", serverId = "s1", bookId = "abs_book1", durationMs = 3_500,
        startPositionMs = 0, tracks = tracks.toList(), chapters = emptyList(),
    )

    private val spans = listOf(TrackSpan(0, 0, 1_000), TrackSpan(1, 1_000, 2_000), TrackSpan(2, 3_000, 500))
    private val chapters = listOf(Chapter(0, "A", 0, 1_500), Chapter(1, "B", 1_500, 3_500))

    private fun map(
        item: Int,
        pos: Long,
        tracks: List<TrackSpan> = spans,
        playing: Boolean = false,
        buffering: Boolean = false,
        error: String? = null,
        sleep: Long? = null,
    ) = toPlaybackState("abs_book1", tracks, item, pos, 3_500, playing, buffering, 1.5f, chapters, sleep, error)

    @Test
    fun `item specs have exact ids urls and metadata, sorted by file index`() {
        val specs = trackItemSpecs(
            book,
            session(
                PlaybackTrack(1, 1_000, 2_000, "audio/mpeg", "http://h/b"),
                PlaybackTrack(0, 0, 1_000, "audio/mp4", "http://h/a"),
            ),
        )
        assertEquals(
            listOf(
                TrackItemSpec("abs_book1/0", "http://h/a", "audio/mp4", "Sample Book", "Sample Author", 0, 1_000),
                TrackItemSpec("abs_book1/1", "http://h/b", "audio/mpeg", "Sample Book", "Sample Author", 1_000, 2_000),
            ),
            specs,
        )
    }

    @Test
    fun `no tracks gives no specs and no spans`() {
        assertTrue(trackItemSpecs(book, session()).isEmpty())
        assertTrue(trackSpans(session()).isEmpty())
    }

    @Test
    fun `spans follow the sorted order so the list position is the item index`() {
        val result = trackSpans(
            session(PlaybackTrack(5, 1_000, 2_000, "m", "u"), PlaybackTrack(3, 0, 1_000, "m", "u")),
        )
        assertEquals(listOf(TrackSpan(0, 0, 1_000), TrackSpan(1, 1_000, 2_000)), result)
    }

    @Test
    fun `the book position adds the start of the file to the position in it`() {
        assertEquals(0L, map(0, 0).positionMs)
        assertEquals(1_250L, map(1, 250).positionMs)
        assertEquals(3_400L, map(2, 400).positionMs)
    }

    @Test
    fun `chapter index changes exactly at the boundary`() {
        assertEquals(0, map(1, 499).chapterIndex) // book 1_499
        assertEquals(1, map(1, 500).chapterIndex) // book 1_500
    }

    @Test
    fun `the end of the last file maps to the book duration`() {
        val s = map(2, 500)
        assertEquals(3_500L, s.positionMs)
        assertEquals(1, s.chapterIndex)
        assertEquals(false, s.isPlaying)
    }

    @Test
    fun `a position past the file length is clamped`() {
        assertEquals(1_000L, map(0, 9_999).positionMs)
    }

    @Test
    fun `flags speed sleep error and chapters pass through`() {
        val s = map(0, 0, playing = true, buffering = true, error = "Oops", sleep = 42)
        assertTrue(s.isPlaying)
        assertTrue(s.isBuffering)
        assertEquals(1.5f, s.speed)
        assertEquals(42L, s.sleepRemainingMs)
        assertEquals("Oops", s.error)
        assertEquals(chapters, s.chapters)
        assertEquals("abs_book1", s.bookId)
        assertEquals(3_500L, s.durationMs)
    }

    @Test
    fun `no tracks gives position zero and no chapter index without chapters`() {
        val s = toPlaybackState(null, emptyList(), 0, 777, 0, false, false, 1f, emptyList(), null, null)
        assertEquals(0L, s.positionMs)
        assertNull(s.chapterIndex)
        assertNull(s.bookId)
        assertNull(s.error)
    }

    @Test
    fun `start failures keep their safe message, a blank one gets the default`() {
        assertEquals("Needs a connection", PlaybackStartException("Needs a connection").message)
        assertEquals("Could not start playback", PlaybackStartException("  ").message)
        assertEquals(PlaybackStartException.DEFAULT_MESSAGE, PlaybackStartException("").message)
    }
}
