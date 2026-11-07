package com.chapterplayer.ui.details

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.browse.BookLinks
import com.chapterplayer.domain.browse.PersonLink
import com.chapterplayer.domain.browse.SeriesLink
import com.chapterplayer.domain.playback.MarkAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookActionsTest {

    private val book = AudioBook(
        id = "a", title = "A Book", author = "Someone", narrator = "", durationMs = 1_000L,
        chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "abs1",
    )

    private fun state(
        playback: DetailsPlayback = DetailsPlayback.NOT_PLAYING,
        canPlay: Boolean = true,
        download: DetailsDownload = DetailsDownload(DetailsDownloadPhase.IDLE, canStart = true),
        links: BookLinks = BookLinks(null, emptyList(), emptyList()),
        favourite: Boolean = false,
        mark: MarkAction = MarkAction.MarkFinished,
        resumeMs: Long = 0L,
    ) = BookDetailsUiState(
        phase = DetailsPhase.LOADED, book = book, playback = playback, canPlay = canPlay,
        cannotPlayReason = if (canPlay) null else "Needs a connection to play", download = download, links = links,
        favourite = favourite, mark = mark, resumePositionMs = resumeMs,
    )

    private fun kinds(items: List<BookActionItem>) = items.map { it.kind }

    @Test
    fun `a book with no series and no other books by the author offers the basic rows`() {
        assertEquals(
            listOf(BookActionKind.PLAY, BookActionKind.FAVOURITE, BookActionKind.MARK, BookActionKind.DOWNLOAD, BookActionKind.DETAILS),
            kinds(bookActionItems(state(), downloadsAvailable = true)),
        )
    }

    @Test
    fun `nothing before the book is known`() {
        assertEquals(emptyList(), bookActionItems(BookDetailsUiState(), downloadsAvailable = true))
    }

    @Test
    fun `series and more by show only when they exist`() {
        val links = BookLinks(SeriesLink("s", "The Series", null, 3), listOf(PersonLink("p", "Someone", 2)), emptyList())
        val items = bookActionItems(state(links = links), downloadsAvailable = true)
        assertEquals(listOf(BookActionKind.SERIES, BookActionKind.MORE_BY, BookActionKind.DETAILS), kinds(items).takeLast(3))
        assertEquals("The Series", items.first { it.kind == BookActionKind.SERIES }.subtitle)
        assertEquals("More by Someone", items.first { it.kind == BookActionKind.MORE_BY }.label)
    }

    @Test
    fun `play follows the page's play button`() {
        assertEquals("Play", bookActionItems(state(), true).first().label)
        assertEquals("Resume", bookActionItems(state(resumeMs = 5L), true).first().label)
        assertEquals("Pause", bookActionItems(state(playback = DetailsPlayback.PLAYING), true).first().label)
        val offline = bookActionItems(state(canPlay = false), true).first()
        assertFalse(offline.enabled)
        assertEquals("Needs a connection to play", offline.subtitle)
        assertFalse(bookActionItems(state(playback = DetailsPlayback.STARTING), true).first().enabled)
    }

    @Test
    fun `favourite and mark say what they will do`() {
        val items = bookActionItems(state(favourite = true, mark = MarkAction.MarkNotFinished), true)
        assertEquals("Remove from favourites", items.first { it.kind == BookActionKind.FAVOURITE }.label)
        assertEquals("Mark as not finished", items.first { it.kind == BookActionKind.MARK }.label)
        assertEquals("Add to favourites", bookActionItems(state(), true).first { it.kind == BookActionKind.FAVOURITE }.label)
    }

    @Test
    fun `the download row follows the download control`() {
        fun row(d: DetailsDownload) = bookActionItems(state(download = d), true).first { it.kind == BookActionKind.DOWNLOAD }
        val offline = row(DetailsDownload(DetailsDownloadPhase.IDLE, canStart = false, disabledReason = "Needs a connection to download"))
        assertFalse(offline.enabled)
        assertEquals("Needs a connection to download", offline.subtitle)
        assertEquals("Pause download", row(DetailsDownload(DetailsDownloadPhase.DOWNLOADING, statusText = "42% · 1m left")).label)
        assertEquals("Resume download", row(DetailsDownload(DetailsDownloadPhase.PAUSED)).label)
        assertEquals("Try download again", row(DetailsDownload(DetailsDownloadPhase.FAILED, canStart = true)).label)
        val done = row(DetailsDownload(DetailsDownloadPhase.DONE, statusText = "Downloaded · 1 GB"))
        assertEquals("Remove download", done.label)
        assertEquals(DetailsDownloadPhase.DONE, done.downloadPhase)
    }

    @Test
    fun `no download rows without downloads on this platform`() {
        val items = bookActionItems(state(download = DetailsDownload(DetailsDownloadPhase.IDLE, canDownloadAnotherCopy = true)), false)
        assertTrue(items.none { it.kind == BookActionKind.DOWNLOAD || it.kind == BookActionKind.DOWNLOAD_ANOTHER })
    }

    @Test
    fun `a merged book gets a row per other copy's download and download another copy`() {
        val s = state(download = DetailsDownload(DetailsDownloadPhase.DONE, statusText = "Downloaded · 1 GB", canDownloadAnotherCopy = true))
            .copy(
                downloadCopyLabel = "ABS · Home",
                otherCopyDownloads = listOf(CopyDownloadUi("p", "Plex · Home Plex", DetailsDownload(DetailsDownloadPhase.PAUSED, statusText = "Paused · 10%"))),
            )
        val items = bookActionItems(s, true)
        assertEquals("ABS · Home · Downloaded · 1 GB", items.first { it.kind == BookActionKind.DOWNLOAD }.subtitle)
        assertTrue(items.any { it.kind == BookActionKind.DOWNLOAD_ANOTHER })
        val copy = items.first { it.kind == BookActionKind.COPY_DOWNLOAD }
        assertEquals("p", copy.copyId)
        assertEquals("Resume download", copy.label)
        assertEquals("Plex · Home Plex · Paused · 10%", copy.subtitle)
        assertNull(items.first { it.kind == BookActionKind.DETAILS }.copyId)
    }
}
