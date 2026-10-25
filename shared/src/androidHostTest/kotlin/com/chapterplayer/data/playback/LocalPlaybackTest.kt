package com.chapterplayer.data.playback

import com.chapterplayer.data.downloads.LocalFile
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.playback.TrackSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LocalPlaybackTest {

    private val bookChapters = listOf(Chapter(0, "One", 0, 4_000), Chapter(1, "Two", 4_000, 6_000))
    private val book = AudioBook(
        id = "abs_book1", title = "Sample Book", author = "Sample Author", narrator = "N",
        durationMs = 6_000, chapters = bookChapters, streamUrl = "", coverUrl = null,
        source = ServerSource.ABS, serverId = "s1",
    )
    private val uri: (String) -> String = { "file://$it" }

    private fun file(ino: String, durationMs: Long = 0, mime: String = "") = LocalFile(ino, "/d/$ino.mp3", durationMs, mime)

    private fun session(vararg tracks: PlaybackTrack, chapters: List<Chapter> = emptyList()) = PlaybackSession(
        sessionId = "sess", serverId = "s1", bookId = "abs_book1", durationMs = 6_100,
        startPositionMs = 2_500, tracks = tracks.toList(), chapters = chapters,
    )

    /** A session track for the server file [ino]. */
    private fun track(index: Int, ino: String, start: Long, duration: Long, mime: String = "audio/mpeg") =
        PlaybackTrack(index, start, duration, mime, "http://server.test/api/items/li_x/file/$ino")

    @Test
    fun `the file id is read from a track address`() {
        assertEquals("ino_7", trackIno("http://server.test/api/items/li_x/file/ino_7"))
        assertEquals("12", trackIno("/api/items/li_x/file/12?x=1"))
        assertNull(trackIno("http://server.test/hls/stream.m3u8"))
    }

    @Test
    fun `offline the stored lengths make the timeline, with the book's chapters and no session`() {
        val playable = playableFromPhone(book, listOf(file("11", 4_000, "audio/mpeg"), file("12", 2_000)), session = null, toUri = uri)!!

        assertEquals(
            listOf(
                PlaybackTrack(0, 0, 4_000, "audio/mpeg", "file:///d/11.mp3"),
                PlaybackTrack(1, 4_000, 2_000, "", "file:///d/12.mp3"),
            ),
            playable.tracks,
        )
        assertNull(playable.session)
        assertEquals(6_000L, playable.durationMs)
        assertEquals(bookChapters, playable.chapters)
        assertEquals(0L, playable.startPositionMs)
        assertEquals("s1", playable.serverId)
        assertEquals(listOf(TrackSpan(0, 0, 4_000), TrackSpan(1, 4_000, 2_000)), trackSpans(playable))
    }

    @Test
    fun `a single file without a stored length takes the book's length`() {
        val playable = playableFromPhone(book, listOf(file("11")), session = null, toUri = uri)!!
        assertEquals(listOf(PlaybackTrack(0, 0, 6_000, "", "file:///d/11.mp3")), playable.tracks)
    }

    @Test
    fun `several files with a length missing cannot play without the server`() {
        assertNull(playableFromPhone(book, listOf(file("11", 4_000), file("12")), session = null, toUri = uri))
    }

    @Test
    fun `no files means nothing to play`() {
        assertNull(playableFromPhone(book, emptyList(), session = null, toUri = uri))
    }

    @Test
    fun `online a session listing the same files gives its timeline and chapters, the audio is the files on the phone`() {
        val chapters = listOf(Chapter(0, "Server", 0, 6_100))
        val s = session(track(2, "12", 4_050, 2_050, "audio/mp4"), track(1, "11", 0, 4_050), chapters = chapters)

        val playable = playableFromPhone(book, listOf(file("11"), file("12")), session = s, toUri = uri)!!

        assertEquals(
            listOf(
                PlaybackTrack(0, 0, 4_050, "audio/mpeg", "file:///d/11.mp3"),
                PlaybackTrack(1, 4_050, 2_050, "audio/mp4", "file:///d/12.mp3"),
            ),
            playable.tracks,
        )
        assertSame(s, playable.session)
        assertEquals(6_100L, playable.durationMs)
        assertEquals(chapters, playable.chapters)
        assertEquals(2_500L, playable.startPositionMs)
    }

    @Test
    fun `session tracks are matched by file id, not by position`() {
        // Same count, but the server lists the files the other way round: each file keeps its own length.
        val s = session(track(0, "12", 0, 2_050), track(1, "11", 2_050, 4_050))

        val playable = playableFromPhone(book, listOf(file("11"), file("12")), session = s, toUri = uri)!!

        assertEquals(
            listOf(
                PlaybackTrack(0, 0, 4_050, "audio/mpeg", "file:///d/11.mp3"),
                PlaybackTrack(1, 4_050, 2_050, "audio/mpeg", "file:///d/12.mp3"),
            ),
            playable.tracks,
        )
    }

    @Test
    fun `a session that lists other files falls back to the stored lengths for the files it does not know`() {
        val s = session(track(0, "11", 0, 4_100), track(1, "99", 4_100, 1_000))

        val playable = playableFromPhone(book, listOf(file("11", 4_000), file("12", 2_000)), session = s, toUri = uri)!!

        assertEquals(
            listOf(
                PlaybackTrack(0, 0, 4_100, "audio/mpeg", "file:///d/11.mp3"),
                PlaybackTrack(1, 4_100, 2_000, "", "file:///d/12.mp3"),
            ),
            playable.tracks,
        )
        assertSame(s, playable.session)
    }

    @Test
    fun `a session with the same number of other files is not taken by position`() {
        val s = session(track(0, "98", 0, 3_000), track(1, "99", 3_000, 3_000))
        assertNull(playableFromPhone(book, listOf(file("11"), file("12")), session = s, toUri = uri))
    }

    @Test
    fun `a stored type wins over the session's`() {
        val s = session(track(0, "11", 0, 6_000, "audio/mpeg"))
        val playable = playableFromPhone(book, listOf(file("11", mime = "audio/x-m4b")), session = s, toUri = uri)!!
        assertEquals("audio/x-m4b", playable.tracks.single().mimeType)
    }

    @Test
    fun `the session's chapters fall back to the book's when it has none`() {
        val s = session(track(0, "11", 0, 6_000))
        assertEquals(bookChapters, playableFromPhone(book, listOf(file("11")), session = s, toUri = uri)!!.chapters)
    }

    @Test
    fun `lengths to remember are the session's, by file id, for files that have none`() {
        val s = session(track(0, "12", 0, 2_050), track(1, "11", 2_050, 4_050))
        assertEquals(mapOf("12" to 2_050L), durationsToRemember(listOf(file("11", 4_000), file("12")), s))
        assertEquals(emptyMap(), durationsToRemember(listOf(file("13")), s), "a file the session does not list")
        assertEquals(emptyMap(), durationsToRemember(listOf(file("11"), file("12")), null))
    }

    @Test
    fun `a streamed session is played as it is`() {
        val s = session(track(1, "12", 1_000, 5_100), track(0, "11", 0, 1_000))

        val playable = streamed(s)

        assertSame(s, playable.session)
        assertEquals(s.tracks, playable.tracks)
        assertEquals(6_100L, playable.durationMs)
        assertEquals(2_500L, playable.startPositionMs)
        assertEquals(listOf(TrackSpan(0, 0, 1_000), TrackSpan(1, 1_000, 5_100)), trackSpans(playable))
        assertEquals(
            listOf(
                TrackItemSpec("abs_book1/0", "http://server.test/api/items/li_x/file/11", "audio/mpeg", "Sample Book", "Sample Author", 0, 1_000),
                TrackItemSpec("abs_book1/1", "http://server.test/api/items/li_x/file/12", "audio/mpeg", "Sample Book", "Sample Author", 1_000, 5_100),
            ),
            trackItemSpecs(book, playable),
        )
    }

    @Test
    fun `item specs for a downloaded book carry the file addresses`() {
        val playable = playableFromPhone(book, listOf(file("11", 4_000), file("12", 2_000)), session = null, toUri = uri)!!
        assertEquals(
            listOf("abs_book1/0" to "file:///d/11.mp3", "abs_book1/1" to "file:///d/12.mp3"),
            trackItemSpecs(book, playable).map { it.mediaId to it.url },
        )
    }

    @Test
    fun `only a book whose audio is files on the phone reads them`() {
        val local = playableFromPhone(book, listOf(file("11")), session = null, toUri = uri)!!
        assertEquals(true, playsFromPhone(local))
        assertEquals(false, playsFromPhone(streamed(session(track(0, "11", 0, 6_000)))))
    }
}
