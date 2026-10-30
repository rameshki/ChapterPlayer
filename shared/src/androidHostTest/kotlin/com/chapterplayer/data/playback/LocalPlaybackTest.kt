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

    // -- Plex downloads ------------------------------------------------------------------------

    private val plexBook = book.copy(id = "plex_1101", source = ServerSource.PLEX, chapters = emptyList(), durationMs = 0)

    /** A Plex session the app built from the album's tracks now: part paths, with each track's rating key as its file id. */
    private fun plexSession(chapters: List<Chapter> = emptyList(), keys: List<String> = listOf("1102", "1103")) = PlaybackSession(
        sessionId = "plex-1101-1", serverId = "s1", bookId = "plex_1101", durationMs = 1_500_000, startPositionMs = 700_000,
        tracks = listOf(
            PlaybackTrack(0, 0, 600_000, "audio/mpeg", "http://plex.test:32400/library/parts/1202/1700000000/file.mp3", fileId = keys[0]),
            PlaybackTrack(1, 600_000, 900_000, "audio/mpeg", "http://plex.test:32400/library/parts/1203/1700000000/file.mp3", fileId = keys[1]),
        ),
        chapters = chapters,
    )

    @Test
    fun `online a plex download whose album changed is not played with that session's reports`() {
        val files = listOf(file("1102", 600_000), file("1103", 900_000))
        // A track was replaced on the server since the download: its positions would land on the wrong tracks.
        assertNull(playableFromPhone(plexBook, files, plexSession(keys = listOf("1102", "9999")), toUri = uri))
        // The same tracks in another order: also not the downloaded book.
        assertNull(playableFromPhone(plexBook, files, plexSession(keys = listOf("1103", "1102")), toUri = uri))
        // Offline (no session) the files play as stored.
        assertEquals(2, playableFromPhone(plexBook, files, session = null, toUri = uri)!!.tracks.size)
    }

    @Test
    fun `a plex session matching the files gives the lengths a download lacks`() {
        val files = listOf(file("1102", 0), file("1103", 900_000))
        assertEquals(mapOf("1102" to 600_000L), durationsToRemember(files, plexSession()))
    }

    @Test
    fun `offline a plex download plays its stored lengths, with one chapter per file when none were stored`() {
        val playable = playableFromPhone(
            plexBook, listOf(file("1102", 600_000, "audio/mpeg"), file("1103", 900_000, "audio/mpeg")), session = null, toUri = uri
        )!!

        assertEquals(
            listOf(
                PlaybackTrack(0, 0, 600_000, "audio/mpeg", "file:///d/1102.mp3"),
                PlaybackTrack(1, 600_000, 900_000, "audio/mpeg", "file:///d/1103.mp3"),
            ),
            playable.tracks,
        )
        assertEquals(1_500_000L, playable.durationMs)
        assertEquals(listOf(Chapter(0, "Part 1", 0, 600_000), Chapter(1, "Part 2", 600_000, 1_500_000)), playable.chapters)
        assertNull(playable.session)
    }

    @Test
    fun `a plex download keeps the chapters stored with the book`() {
        val chapters = listOf(Chapter(0, "Opening", 0, 1_500_000))
        val playable = playableFromPhone(
            plexBook.copy(chapters = chapters), listOf(file("1102", 600_000), file("1103", 900_000)), session = null, toUri = uri
        )!!
        assertEquals(chapters, playable.chapters)
    }

    @Test
    fun `online a plex download plays the files and keeps the session for the reports`() {
        val chapters = listOf(Chapter(0, "Server", 0, 1_500_000))
        val s = plexSession(chapters)

        val playable = playableFromPhone(plexBook, listOf(file("1102", 600_000), file("1103", 900_000)), s, toUri = uri)!!

        assertEquals(listOf("file:///d/1102.mp3", "file:///d/1103.mp3"), playable.tracks.map { it.url })
        assertEquals(listOf(0L, 600_000L), playable.tracks.map { it.startMs })
        assertSame(s, playable.session)
        assertEquals(700_000L, playable.startPositionMs)
        assertEquals(chapters, playable.chapters)
    }

    @Test
    fun `an abs book without chapters keeps none`() {
        val playable = playableFromPhone(book.copy(chapters = emptyList()), listOf(file("11", 4_000), file("12", 2_000)), null, uri)!!
        assertEquals(emptyList(), playable.chapters)
    }
}
