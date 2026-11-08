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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenWidgetStateTest {

    private val chapters = listOf(Chapter(0, "Opening", 0, 600_000), Chapter(1, "Second", 600_000, 3_600_000))

    private fun book(id: String, durationMs: Long = 3_600_000, chapters: List<Chapter> = emptyList(), serverId: String = "s1") = AudioBook(
        id = id, title = "Title $id", author = "Author $id", narrator = "Narrator", durationMs = durationMs,
        chapters = chapters, streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = serverId,
    )

    private fun item(
        id: String,
        positionMs: Long? = null,
        lastPlayedAt: Long = 0,
        state: DownloadState = DownloadState.NOT_DOWNLOADED,
        serverId: String = "s1",
    ): BrowseItem {
        val progress = positionMs?.let { ReadingProgress(id, it, 3_600_000, lastPlayedAt, false) }
        return BrowseItem(book(id, serverId = serverId), progress, state)
    }

    private fun listen(playback: PlaybackState, loaded: AudioBook?, items: List<BrowseItem>, online: Boolean = true, downloadedOnly: Boolean = false) =
        listenWidgetState(playback, loaded, items, online, downloadedOnly)

    // -- Which state ---------------------------------------------------------------------------

    @Test
    fun nothingEverPlayedSaysSo() {
        val shown = assertIs<ListenWidgetState.Empty>(listen(PlaybackState(), null, listOf(item("a"))))
        assertFalse(shown.everPlayed)
        assertEquals("Nothing played yet", listenEmptyText(shown))
    }

    @Test
    fun aPlayedBookThatCannotPlayNowIsNotCalledNeverPlayed() {
        val items = listOf(item("streamed", positionMs = 900_000, lastPlayedAt = 3))
        val shown = assertIs<ListenWidgetState.Empty>(listen(PlaybackState(), null, items, online = false))
        assertTrue(shown.everPlayed)
        assertEquals("Nothing to resume now", listenEmptyText(shown))
    }

    @Test
    fun nothingLoadedOffersTheContinueBook() {
        val items = listOf(item("a", positionMs = 900_000, lastPlayedAt = 2), item("b", positionMs = 100, lastPlayedAt = 1))
        val shown = listen(PlaybackState(), null, items)
        assertEquals(ListenWidgetState.Resume("a", "Title a", "Author a", percent = 25, timeLeftText = "45 min left"), shown)
    }

    @Test
    fun aLoadedBookShowsTheControlsPlayingOrPaused() {
        val playing = PlaybackState(bookId = "a", isPlaying = true, positionMs = 900_000, durationMs = 3_600_000, chapterIndex = 1, chapters = chapters)
        val shown = assertIs<ListenWidgetState.Controls>(listen(playing, book("a", chapters = chapters), emptyList()))
        assertEquals(ListenWidgetState.Controls("a", "Title a", "Author a", "Second", percent = 25, isPlaying = true, canSkip = true, canJumpChapters = true), shown)

        val paused = assertIs<ListenWidgetState.Controls>(listen(playing.copy(isPlaying = false), book("a", chapters = chapters), emptyList()))
        assertFalse(paused.isPlaying)
    }

    @Test
    fun aLoadedBookWinsOverTheResumeBook() {
        val items = listOf(item("b", positionMs = 900_000, lastPlayedAt = 9))
        val shown = listen(PlaybackState(bookId = "a", durationMs = 1_000), book("a"), items)
        assertEquals("a", assertIs<ListenWidgetState.Controls>(shown).bookId)
    }

    @Test
    fun releasingThePlayerGoesBackToResume() {
        val items = listOf(item("a", positionMs = 900_000, lastPlayedAt = 9))
        assertIs<ListenWidgetState.Controls>(listen(PlaybackState(bookId = "a", durationMs = 3_600_000), book("a"), items))
        // After the idle stop the player is empty again (PlaybackState()): the same book is offered to resume.
        assertEquals("a", assertIs<ListenWidgetState.Resume>(listen(PlaybackState(), null, items)).bookId)
    }

    @Test
    fun chapterButtonsNeedTwoChapters() {
        val one = PlaybackState(bookId = "a", durationMs = 1_000, chapterIndex = 0, chapters = chapters.take(1))
        assertFalse(assertIs<ListenWidgetState.Controls>(listen(one, book("a"), emptyList())).canJumpChapters)
        val none = PlaybackState(bookId = "a", durationMs = 1_000)
        assertFalse(assertIs<ListenWidgetState.Controls>(listen(none, book("a"), emptyList())).canJumpChapters)
    }

    @Test
    fun theControlsUseTheNowPlayingRulesForTitleAndButtons() {
        // Unknown book: the fallback title, never another book's name; buffering with play asked for offers Pause.
        val state = PlaybackState(bookId = "a", isBuffering = true, playWhenReady = true, durationMs = 0)
        val shown = assertIs<ListenWidgetState.Controls>(listen(state, book("other"), emptyList()))
        assertEquals(NOW_PLAYING_FALLBACK_TITLE, shown.title)
        assertTrue(shown.isPlaying)
        assertFalse(shown.canSkip)
        assertEquals(0, shown.percent)
    }

    @Test
    fun positionTicksInsideOnePercentDoNotChangeTheState() {
        val first = PlaybackState(bookId = "a", isPlaying = true, positionMs = 900_000, durationMs = 3_600_000)
        val later = first.copy(positionMs = 930_000) // 25.8%: still 25
        assertEquals(listen(first, book("a"), emptyList()), listen(later, book("a"), emptyList()))
        val next = first.copy(positionMs = 936_000) // 26%
        assertEquals(26, assertIs<ListenWidgetState.Controls>(listen(next, book("a"), emptyList())).percent)
    }

    // -- Both widgets agree --------------------------------------------------------------------

    @Test
    fun theResumeBookIsAlwaysTheContinueWidgetsBook() {
        val merged = item("abs_x", positionMs = 900_000, lastPlayedAt = 5).copy(
            sources = listOf(
                item("abs_x", positionMs = 900_000, lastPlayedAt = 5),
                item("plex_x", positionMs = 800_000, lastPlayedAt = 1, state = DownloadState.DOWNLOADED, serverId = "s2"),
            ),
        )
        val cases = listOf(
            listOf(item("a", positionMs = 900_000, lastPlayedAt = 2), item("b", positionMs = 100, lastPlayedAt = 1)),
            listOf(item("streamed", positionMs = 900_000, lastPlayedAt = 9), item("onPhone", positionMs = 1_800_000, lastPlayedAt = 1, state = DownloadState.DOWNLOADED)),
            listOf(merged),
            emptyList(),
        )
        for (items in cases) for (online in listOf(true, false)) for (downloadedOnly in listOf(true, false)) {
            val continueBook = (continueWidgetState(items, online, downloadedOnly) as? ContinueWidgetState.Book)?.bookId
            val listenBook = (listen(PlaybackState(), null, items, online, downloadedOnly) as? ListenWidgetState.Resume)?.bookId
            assertEquals(continueBook, listenBook, "items=${items.map { it.book.id }} online=$online downloadedOnly=$downloadedOnly")
        }
        // A merged book with a downloaded copy resumes that copy, as Continue Listening does.
        assertEquals("plex_x", assertIs<ListenWidgetState.Resume>(listen(PlaybackState(), null, listOf(merged))).bookId)
    }

    // -- Sizes ---------------------------------------------------------------------------------

    @Test
    fun sizeClasses() {
        assertEquals(ListenSize.COMPACT, listenSize(110f, 40f))
        assertEquals(ListenSize.COMPACT, listenSize(180f, 160f)) // two cells wide is one row whatever the height
        assertEquals(ListenSize.COMPACT, listenSize(300f, 60f)) // one cell tall
        assertEquals(ListenSize.SHORT, listenSize(LISTEN_WIDE_MIN_DP, LISTEN_SHORT_MIN_HEIGHT_DP))
        assertEquals(ListenSize.SHORT, listenSize(320f, 130f)) // a 4x2 held sideways, or a low two-row launcher
        assertEquals(ListenSize.STANDARD, listenSize(LISTEN_WIDE_MIN_DP, LISTEN_STACKED_MIN_HEIGHT_DP))
        assertEquals(ListenSize.STANDARD, listenSize(320f, 220f))
        assertEquals(ListenSize.EXPANDED, listenSize(LISTEN_WIDE_MIN_DP, LISTEN_EXPANDED_MIN_HEIGHT_DP))
        assertEquals(ListenSize.EXPANDED, listenSize(320f, 330f))
    }

    @Test
    fun whatEachSizeShows() {
        val controls = ListenWidgetState.Controls("a", "T", "A", "C", 10, isPlaying = true, canSkip = true, canJumpChapters = true)
        assertEquals(
            ListenLayout(showCover = false, showSecondLine = false, showProgress = false, showSkips = false, showChapterButtons = false, controlsBesideTitle = false),
            listenLayout(ListenSize.COMPACT, controls),
        )
        // Low: one row, the title and chapter beside back 15 / play-pause / forward 30; no progress row, no chapter buttons.
        assertEquals(
            ListenLayout(showCover = false, showSecondLine = true, showProgress = false, showSkips = true, showChapterButtons = false, controlsBesideTitle = true),
            listenLayout(ListenSize.SHORT, controls),
        )
        assertEquals(
            ListenLayout(showCover = false, showSecondLine = true, showProgress = true, showSkips = true, showChapterButtons = true, controlsBesideTitle = false),
            listenLayout(ListenSize.STANDARD, controls),
        )
        assertEquals(
            ListenLayout(showCover = true, showSecondLine = true, showProgress = true, showSkips = true, showChapterButtons = true, controlsBesideTitle = false),
            listenLayout(ListenSize.EXPANDED, controls),
        )

        // No chapters, unknown length: those buttons are hidden in every size.
        val bare = controls.copy(canSkip = false, canJumpChapters = false)
        ListenSize.entries.forEach { size ->
            assertFalse(listenLayout(size, bare).showSkips)
            assertFalse(listenLayout(size, bare).showChapterButtons)
        }

        // Resume: the low size draws as the standard one (it fits); the cover only when expanded; Empty: no cover.
        val resume = ListenWidgetState.Resume("a", "T", "A", 25, "1 h left")
        assertEquals(listenLayout(ListenSize.STANDARD, resume), listenLayout(ListenSize.SHORT, resume))
        assertTrue(listenLayout(ListenSize.SHORT, resume).showProgress)
        assertTrue(listenLayout(ListenSize.EXPANDED, resume).showCover)
        assertFalse(listenLayout(ListenSize.STANDARD, resume).showCover)
        assertFalse(listenLayout(ListenSize.EXPANDED, resume).showSkips)
        assertFalse(listenLayout(ListenSize.EXPANDED, ListenWidgetState.Empty(everPlayed = true)).showCover)
    }

    @Test
    fun everyLayoutFitsItsLowestHeightAtLargeText() {
        val controls = ListenWidgetState.Controls("a", "T", "A", "C", 10, isPlaying = true, canSkip = true, canJumpChapters = true)
        val resume = ListenWidgetState.Resume("a", "T", "A", 25, "1 h left")
        val lowest = mapOf(
            ListenSize.SHORT to LISTEN_SHORT_MIN_HEIGHT_DP,
            ListenSize.STANDARD to LISTEN_STACKED_MIN_HEIGHT_DP,
            ListenSize.EXPANDED to LISTEN_EXPANDED_MIN_HEIGHT_DP,
        )
        for (fontScale in listOf(1.0f, 1.3f)) for ((size, height) in lowest) for (state in listOf(controls, resume)) {
            val needed = listenContentHeightDp(listenLayout(size, state), state, fontScale)
            assertTrue(needed <= height, "$size ${state::class.simpleName} at font scale $fontScale needs $needed dp of $height")
        }
        // The old standard layout (stacked controls) did not fit 110 dp: that is why the low size has one row.
        val stackedAtLow = listenContentHeightDp(listenLayout(ListenSize.STANDARD, controls), controls, 1.3f)
        assertTrue(stackedAtLow > LISTEN_SHORT_MIN_HEIGHT_DP)
    }

    @Test
    fun theControlsRowFitsEveryWidth() {
        for (width in listOf(LISTEN_WIDE_MIN_DP, 232f, LISTEN_ROOMY_MIN_DP, 360f)) {
            val m = listenControlsMetrics(width)
            val row = 4 * m.sideButtonDp + m.playButtonDp + 2 * m.horizontalPaddingDp
            assertTrue(row <= width, "five buttons at $width dp need $row dp")
            assertTrue(m.sideButtonDp >= 40f && m.playButtonDp >= 44f, "touch targets at $width dp")
        }
        // Wide enough: every button is 48 dp.
        assertEquals(ListenControlsMetrics(sideButtonDp = 48f, playButtonDp = 48f, horizontalPaddingDp = 12f), listenControlsMetrics(LISTEN_ROOMY_MIN_DP))
    }

    // -- Texts ---------------------------------------------------------------------------------

    @Test
    fun texts() {
        val resume = ListenWidgetState.Resume("a", "Dune", "Frank Herbert", 25, "6 h left")
        assertEquals("25% · 6 h left", listenProgressText(resume))
        assertEquals("Play Dune", listenPlayDescription(resume))

        val controls = ListenWidgetState.Controls("a", "Dune", "Frank Herbert", "Chapter 3", 40, isPlaying = true, canSkip = true, canJumpChapters = true)
        assertEquals("Chapter 3", listenSecondLine(controls))
        assertEquals("Frank Herbert", listenSecondLine(controls.copy(chapterTitle = null)))
        assertEquals("40%", listenProgressText(controls))
        assertEquals("Pause", listenPlayDescription(controls))
        assertEquals("Play", listenPlayDescription(controls.copy(isPlaying = false)))
        assertEquals("Frank Herbert", listenSecondLine(resume))
        assertNull(listenSecondLine(ListenWidgetState.Empty(everPlayed = false)))
    }

    @Test
    fun theCoverBookIsTheBookShown() {
        assertEquals("a", ListenWidgetState.Resume("a", "T", "A", 1, "x").coverBookId)
        assertEquals("b", ListenWidgetState.Controls("b", "T", "A", null, 1, false, true, false).coverBookId)
        assertNull(ListenWidgetState.Empty(everPlayed = true).coverBookId)
    }

    // -- Cover fetching ------------------------------------------------------------------------

    @Test
    fun aLoadedCoverRedrawsOnceAndACachedOneDoesNothing() {
        assertEquals(CoverStep.Redraw, coverNextStep(CoverFetch.LOADED, online = true, attempts = 1))
        assertEquals(CoverStep.Done, coverNextStep(CoverFetch.CACHED, online = true, attempts = 1))
    }

    @Test
    fun aFailedCoverIsTriedAgainAfterTheBackoffWhileOnline() {
        // The loader refuses the same book for 60 s after a failure, so the retry waits longer than that.
        val retry = assertIs<CoverStep.RetryAfter>(coverNextStep(CoverFetch.FAILED, online = true, attempts = 1))
        assertTrue(retry.delayMs > 60_000L)
        // Offline nothing is tried: the network's return starts it again.
        assertEquals(CoverStep.Done, coverNextStep(CoverFetch.FAILED, online = false, attempts = 1))
        // A book with no cover at all is not asked for forever.
        assertEquals(CoverStep.Done, coverNextStep(CoverFetch.FAILED, online = true, attempts = COVER_MAX_ATTEMPTS))
    }

    @Test
    fun theWidgetCoverIsSmall() {
        // 256 x 256 RGB_565 is 128 KiB; two layouts carry it (expanded and expanded wide): 256 KiB per placed widget.
        assertEquals(256, WIDGET_COVER_MAX_SIDE)
        assertEquals(131_072L, widgetCoverBytes(256, 256))
        assertEquals(256L * 170 * 2, widgetCoverBytes(256, 170))
    }
}
