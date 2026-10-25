package com.chapterplayer.domain.widgets

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WidgetStateTest {

    private val chapters = listOf(Chapter(0, "Opening", 0, 600_000), Chapter(1, "Second", 600_000, 3_600_000))

    private fun book(id: String, durationMs: Long = 3_600_000, chapters: List<Chapter> = emptyList()) = AudioBook(
        id = id, title = "Title $id", author = "Author $id", narrator = "Narrator", durationMs = durationMs,
        chapters = chapters, streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
    )

    private fun item(
        id: String,
        positionMs: Long? = null,
        lastPlayedAt: Long = 0,
        state: DownloadState = DownloadState.NOT_DOWNLOADED,
        durationMs: Long = 3_600_000,
    ): BrowseItem {
        val progress = positionMs?.let { ReadingProgress(id, it, durationMs, lastPlayedAt, false) }
        return BrowseItem(book(id, durationMs), progress, state)
    }

    // -- Now Playing --------------------------------------------------------------------------

    @Test
    fun nothingLoadedIsIdle() {
        assertEquals(NowPlayingWidgetState.Idle, nowPlayingWidgetState(PlaybackState(), null))
        // A book in the repository but nothing in the player is still idle.
        assertEquals(NowPlayingWidgetState.Idle, nowPlayingWidgetState(PlaybackState(), book("a")))
    }

    @Test
    fun loadedBookShowsTitleAuthorAndChapter() {
        val state = PlaybackState(bookId = "a", isPlaying = true, positionMs = 700_000, durationMs = 3_600_000, chapterIndex = 1, chapters = chapters)
        val shown = nowPlayingWidgetState(state, book("a", chapters = chapters))
        assertEquals(NowPlayingWidgetState.Book("Title a", "Author a", "Second", isPlaying = true, canSkip = true), shown)
    }

    @Test
    fun bufferingWithPlayAskedForShowsPause() {
        val state = PlaybackState(bookId = "a", isBuffering = true, playWhenReady = true, durationMs = 1_000)
        assertIs<NowPlayingWidgetState.Book>(nowPlayingWidgetState(state, book("a"))).let { assertEquals(true, it.isPlaying) }
        val paused = state.copy(playWhenReady = false)
        assertIs<NowPlayingWidgetState.Book>(nowPlayingWidgetState(paused, book("a"))).let { assertEquals(false, it.isPlaying) }
    }

    @Test
    fun noChaptersMeansNoChapterTitle() {
        val state = PlaybackState(bookId = "a", durationMs = 1_000, chapterIndex = 5, chapters = emptyList())
        assertIs<NowPlayingWidgetState.Book>(nowPlayingWidgetState(state, book("a"))).let { assertEquals(null, it.chapterTitle) }
    }

    @Test
    fun unknownBookStillShowsAFallbackTitle() {
        val state = PlaybackState(bookId = "a", durationMs = 1_000)
        val shown = assertIs<NowPlayingWidgetState.Book>(nowPlayingWidgetState(state, null))
        assertEquals(NOW_PLAYING_FALLBACK_TITLE, shown.title)
        assertEquals("", shown.author)
        // A book that is not the loaded one is never shown.
        val other = assertIs<NowPlayingWidgetState.Book>(nowPlayingWidgetState(state, book("b")))
        assertEquals(NOW_PLAYING_FALLBACK_TITLE, other.title)
    }

    @Test
    fun skippingNeedsAKnownLength() {
        val state = PlaybackState(bookId = "a", durationMs = 0)
        assertIs<NowPlayingWidgetState.Book>(nowPlayingWidgetState(state, book("a"))).let { assertEquals(false, it.canSkip) }
    }

    @Test
    fun positionTicksDoNotChangeTheWidgetState() {
        val first = PlaybackState(bookId = "a", isPlaying = true, positionMs = 1_000, durationMs = 3_600_000, chapterIndex = 0, chapters = chapters)
        val later = first.copy(positionMs = 11_000)
        assertEquals(nowPlayingWidgetState(first, book("a", chapters = chapters)), nowPlayingWidgetState(later, book("a", chapters = chapters)))
    }

    // -- Continue Listening -------------------------------------------------------------------

    @Test
    fun nothingStartedIsEmpty() {
        assertEquals(ContinueWidgetState.Empty, continueWidgetState(emptyList(), online = true, downloadedOnly = false))
        assertEquals(ContinueWidgetState.Empty, continueWidgetState(listOf(item("a")), online = true, downloadedOnly = false))
    }

    @Test
    fun showsTheResumeCardBook() {
        val items = listOf(item("a", positionMs = 900_000, lastPlayedAt = 2), item("b", positionMs = 100, lastPlayedAt = 1))
        val shown = continueWidgetState(items, online = true, downloadedOnly = false)
        assertEquals(ContinueWidgetState.Book("a", "Title a", "Author a", percent = 25, timeLeftText = "45 min left"), shown)
    }

    @Test
    fun offlineSkipsBooksThatAreNotDownloaded() {
        val items = listOf(
            item("streamed", positionMs = 900_000, lastPlayedAt = 9),
            item("onPhone", positionMs = 1_800_000, lastPlayedAt = 1, state = DownloadState.DOWNLOADED),
        )
        val offline = assertIs<ContinueWidgetState.Book>(continueWidgetState(items, online = false, downloadedOnly = false))
        assertEquals("onPhone", offline.bookId)
        val downloadedOnly = assertIs<ContinueWidgetState.Book>(continueWidgetState(items, online = true, downloadedOnly = true))
        assertEquals("onPhone", downloadedOnly.bookId)
        val online = assertIs<ContinueWidgetState.Book>(continueWidgetState(items, online = true, downloadedOnly = false))
        assertEquals("streamed", online.bookId)
    }

    @Test
    fun offlineWithNothingDownloadedIsEmpty() {
        val items = listOf(item("streamed", positionMs = 900_000, lastPlayedAt = 9))
        assertEquals(ContinueWidgetState.Empty, continueWidgetState(items, online = false, downloadedOnly = false))
    }

    @Test
    fun percentIsRoundedDownAndKeptInRange() {
        val items = listOf(item("a", positionMs = 3_540_000, durationMs = 3_600_000))
        val shown = assertIs<ContinueWidgetState.Book>(continueWidgetState(items, online = true, downloadedOnly = false))
        assertEquals(98, shown.percent)
    }
}
