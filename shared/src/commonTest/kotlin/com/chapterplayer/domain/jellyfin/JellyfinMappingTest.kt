package com.chapterplayer.domain.jellyfin

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinChapterDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinPersonDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinUserDataDto
import com.chapterplayer.domain.playback.ServerProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinMappingTest {

    private val hourTicks = 36_000_000_000L
    private val hourMs = 3_600_000L
    private val id = "0bdd952d73af33d8d4421dfdb2f25919"

    private fun item12() = JellyfinItemDto(
        id = id, name = "The Harbour Lights", type = "AudioBook", runTimeTicks = 10 * hourTicks,
        albumArtist = "Album Artist", artists = listOf("Some Artist"),
        people = listOf(
            JellyfinPersonDto("Ada Example", "Author"),
            JellyfinPersonDto("Ned Reader", "Narrator"),
            JellyfinPersonDto("Second Voice", "Narrator"),
            JellyfinPersonDto("A Director", "Director"),
        ),
        imageTags = mapOf("Primary" to "7d1c2b3a"),
        dateCreated = "2026-10-01T12:00:00.0000000Z",
        seriesName = "Harbour Tales",
    )

    @Test
    fun `a 12 item maps with author narrator duration and cover`() {
        val book = jellyfinItemToBook(item12(), "https://jf.test/jellyfin", "row-1", "view-1")!!
        assertEquals("jf_$id", book.id)
        assertEquals("The Harbour Lights", book.title)
        assertEquals("Ada Example", book.author)
        assertEquals("Ned Reader, Second Voice", book.narrator)
        assertEquals(10 * hourMs, book.durationMs)
        assertEquals("https://jf.test/jellyfin/Items/$id/Images/Primary?tag=7d1c2b3a&maxWidth=600", book.coverUrl)
        assertEquals(1_790_856_000_000L, book.addedAt)
        assertEquals("Harbour Tales", book.seriesName)
        assertEquals("", book.streamUrl)
        assertEquals(ServerSource.JELLYFIN, book.source)
        assertEquals("row-1", book.serverId)
        assertEquals("view-1", book.libraryId)
    }

    @Test
    fun `the cover address keeps the path once and has no token`() {
        val book = jellyfinItemToBook(item12(), "https://jf.test/jellyfin/", "row-1", "v")!!
        assertEquals("https://jf.test/jellyfin/Items/$id/Images/Primary?tag=7d1c2b3a&maxWidth=600", book.coverUrl)
        val cover = book.coverUrl!!
        assertFalse(cover.contains("api_key", ignoreCase = true) || cover.contains("Token", ignoreCase = true))
    }

    @Test
    fun `a 10-10 item takes the album artist and has no narrator`() {
        val old = item12().copy(people = emptyList())
        val book = jellyfinItemToBook(old, "https://jf.test", "row-1", "v")!!
        assertEquals("Album Artist", book.author)
        assertEquals("", book.narrator)
        val noAlbumArtist = jellyfinItemToBook(old.copy(albumArtist = null), "https://jf.test", "row-1", "v")!!
        assertEquals("Some Artist", noAlbumArtist.author)
    }

    @Test
    fun `item with missing fields maps safely`() {
        val bare = JellyfinItemDto(id = id, name = "Bare", type = "AudioBook")
        val book = jellyfinItemToBook(bare, "https://jf.test", "row-1", "v")!!
        assertEquals(0L, book.durationMs)
        assertEquals("", book.author)
        assertEquals("", book.narrator)
        assertNull(book.coverUrl)
        assertEquals(0L, book.addedAt)
        assertNull(book.seriesName)
        val negative = jellyfinItemToBook(bare.copy(runTimeTicks = -5, dateCreated = "not a date"), "https://jf.test", "row-1", "v")!!
        assertEquals(0L, negative.durationMs)
        assertEquals(0L, negative.addedAt)
        val noName = jellyfinItemToBook(bare.copy(name = null), "https://jf.test", "row-1", "v")!!
        assertEquals("", noName.title)
    }

    @Test
    fun `only audiobook items with an id are books`() {
        assertNull(jellyfinItemToBook(item12().copy(id = ""), "https://jf.test", "row-1", "v"))
        assertNull(jellyfinItemToBook(item12().copy(id = null), "https://jf.test", "row-1", "v"))
        assertNull(jellyfinItemToBook(item12().copy(type = "Audio"), "https://jf.test", "row-1", "v"))
        assertNull(jellyfinItemToBook(item12().copy(type = null), "https://jf.test", "row-1", "v"))
    }

    // ---- chapters ----

    @Test
    fun `chapters are sorted and end where the next starts`() {
        val chapters = listOf(
            JellyfinChapterDto(4 * hourTicks, "Two"),
            JellyfinChapterDto(12 * hourTicks, "Past the end"),
            JellyfinChapterDto(0, "One"),
        )
        assertEquals(
            listOf(Chapter(0, "One", 0, 4 * hourMs), Chapter(1, "Two", 4 * hourMs, 10 * hourMs)),
            jellyfinChapters(chapters, 10 * hourMs),
        )
    }

    @Test
    fun `duplicate starts and starts at the end are dropped`() {
        val chapters = listOf(
            JellyfinChapterDto(0, "One"), JellyfinChapterDto(0, "One again"),
            JellyfinChapterDto(hourTicks, null), JellyfinChapterDto(2 * hourTicks, "At the end"),
            JellyfinChapterDto(null, "No start"),
        )
        assertEquals(
            listOf(Chapter(0, "One", 0, hourMs), Chapter(1, "Chapter 2", hourMs, 2 * hourMs)),
            jellyfinChapters(chapters, 2 * hourMs),
        )
    }

    @Test
    fun `no chapters is one part`() {
        assertEquals(listOf(Chapter(0, "Part 1", 0, 3 * hourMs)), jellyfinChapters(emptyList(), 3 * hourMs))
        assertEquals(listOf(Chapter(0, "Part 1", 0, 0)), jellyfinChapters(listOf(JellyfinChapterDto(0, "One")), 0))
        // Every chapter past the end is the same as none.
        assertEquals(listOf(Chapter(0, "Part 1", 0, hourMs)), jellyfinChapters(listOf(JellyfinChapterDto(2 * hourTicks, "x")), hourMs))
    }

    @Test
    fun `a first chapter after zero starts the book at zero`() {
        val chapters = jellyfinChapters(listOf(JellyfinChapterDto(hourTicks, "Late")), 2 * hourMs)
        assertEquals(0L, chapters.first().startMs)
        assertEquals(2 * hourMs, chapters.last().endMs)
    }

    // ---- progress ----

    @Test
    fun `played is finished at the end`() {
        val data = JellyfinUserDataDto(playbackPositionTicks = 0, played = true, lastPlayedDate = "2026-10-25T21:00:00.0000000Z")
        assertEquals(ServerProgress(3 * hourMs, 3 * hourMs, true, 1_792_962_000_000L), jellyfinServerProgress(data, 3 * hourMs))
    }

    @Test
    fun `zero and not played is no progress`() {
        assertNull(jellyfinServerProgress(JellyfinUserDataDto(playbackPositionTicks = 0, played = false), hourMs))
        assertNull(jellyfinServerProgress(JellyfinUserDataDto(), hourMs))
        assertNull(jellyfinServerProgress(null, hourMs))
    }

    @Test
    fun `in progress is the exact position at the last played time`() {
        val data = JellyfinUserDataDto(playbackPositionTicks = 13_500_000_000, played = false, lastPlayedDate = "2026-10-20T08:30:00.0000000Z")
        assertEquals(ServerProgress(1_350_000L, 10 * hourMs, false, 1_792_485_000_000L), jellyfinServerProgress(data, 10 * hourMs))
    }

    @Test
    fun `no last played date is never newer`() {
        val p = jellyfinServerProgress(JellyfinUserDataDto(playbackPositionTicks = 10_000_000), hourMs)!!
        assertEquals(0L, p.updatedAtMs)
        assertEquals(1_000L, p.positionMs)
    }

    @Test
    fun `a position past the end stays at the end`() {
        val p = jellyfinServerProgress(JellyfinUserDataDto(playbackPositionTicks = 2 * hourTicks), hourMs)!!
        assertEquals(hourMs, p.positionMs)
        assertTrue(!p.isFinished)
    }
}
