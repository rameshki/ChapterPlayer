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

    private val controls = ListenWidgetState.Controls("a", "T", "A", "C", 10, isPlaying = true, canSkip = true, canJumpChapters = true)
    private val resume = ListenWidgetState.Resume("a", "T", "A", 25, "1 h left")

    /** Each size class's smallest box (the Glance buckets) and a few wider or taller ones that land in it. */
    private val boxes = mapOf(
        ListenSize.MINI to listOf(110f to 40f, 200f to 60f, 240f to 159f),
        ListenSize.BAR to listOf(LISTEN_BAR_MIN_WIDTH_DP to LISTEN_BAR_MIN_HEIGHT_DP, 276f to 102f, 360f to 140f),
        ListenSize.SQUARE to listOf(LISTEN_SQUARE_MIN_WIDTH_DP to LISTEN_TALL_MIN_HEIGHT_DP, 130f to 220f, 203f to 337f),
        ListenSize.WIDE to listOf(LISTEN_WIDE_MIN_WIDTH_DP to LISTEN_TALL_MIN_HEIGHT_DP, 276f to 220f, 360f to 240f),
        ListenSize.LARGE to listOf(LISTEN_WIDE_MIN_WIDTH_DP to LISTEN_LARGE_MIN_HEIGHT_DP, 276f to 337f, 360f to 455f),
    )

    @Test
    fun sizeClasses() {
        boxes.forEach { (size, list) -> list.forEach { (w, h) -> assertEquals(size, listenSize(w, h), "$w x $h") } }
        // A 4x1 held sideways (about 51 dp) is too low for the bar with its cover: the mini bar.
        assertEquals(ListenSize.MINI, listenSize(554f, 51f))
        // Two cells wide is never the bar: there is no room for the cover, the title and the buttons.
        assertEquals(ListenSize.MINI, listenSize(130f, 102f))
    }

    @Test
    fun theCoverIsInEverySizeButTheMiniBar() {
        for ((size, list) in boxes) for ((w, h) in list) for (state in listOf(controls, resume)) {
            val layout = listenLayout(w, h, state)
            if (size == ListenSize.MINI) assertEquals(0f, layout.coverDp) else assertTrue(layout.coverDp > 0f, "$size cover")
        }
        // Bigger sizes, bigger covers.
        val covers = listOf(ListenSize.BAR, ListenSize.SQUARE, ListenSize.WIDE, ListenSize.LARGE).map { size ->
            val (w, h) = boxes.getValue(size).first()
            listenLayout(w, h, resume).coverDp
        }
        assertEquals(covers.sorted(), covers)
        assertFalse(listenLayout(110f, 40f, ListenWidgetState.Empty(everPlayed = true)).coverDp > 0f)
    }

    @Test
    fun whatEachSizeShows() {
        val bar = listenLayout(LISTEN_BAR_MIN_WIDTH_DP, LISTEN_BAR_MIN_HEIGHT_DP, controls)
        assertTrue(bar.showSkips) // play/pause with back 15 and forward 30
        assertFalse(bar.showChapterButtons)
        assertFalse(bar.showProgress)
        assertTrue(bar.showSecondLine)

        val square = listenLayout(LISTEN_SQUARE_MIN_WIDTH_DP, LISTEN_TALL_MIN_HEIGHT_DP, resume)
        assertTrue(square.showMeta) // "1 h left" beside the play button
        assertFalse(square.showProgress)
        assertFalse(square.showSkips)

        val wide = listenLayout(LISTEN_WIDE_MIN_WIDTH_DP, LISTEN_TALL_MIN_HEIGHT_DP, resume)
        assertTrue(wide.showSecondLine && wide.showProgress && wide.showMeta)
        assertFalse(wide.showSkips) // Resume: Play only
        val wideControls = listenLayout(LISTEN_WIDE_MIN_WIDTH_DP, LISTEN_TALL_MIN_HEIGHT_DP, controls)
        assertTrue(wideControls.showSkips)
        assertFalse(wideControls.showChapterButtons) // no room beside the cover at 250 dp
        assertTrue(listenLayout(380f, 200f, controls).showChapterButtons) // they fit when wider

        val large = listenLayout(LISTEN_WIDE_MIN_WIDTH_DP, LISTEN_LARGE_MIN_HEIGHT_DP, controls)
        assertTrue(large.showSkips && large.showChapterButtons && large.showProgress && large.showAuthorAndChapter)
        assertEquals(2, large.titleLines)

        val mini = listenLayout(110f, 40f, controls)
        assertFalse(mini.showSkips || mini.showSecondLine || mini.showProgress)

        // No chapters, unknown length: those buttons are hidden in every size.
        val bare = controls.copy(canSkip = false, canJumpChapters = false)
        for ((_, list) in boxes) for ((w, h) in list) {
            assertFalse(listenLayout(w, h, bare).showSkips)
            assertFalse(listenLayout(w, h, bare).showChapterButtons)
        }
    }

    @Test
    fun everyLayoutFitsItsBoxAtLargeText() {
        for (fontScale in listOf(1.0f, 1.3f)) for ((size, list) in boxes) for ((w, h) in list) for (state in listOf(controls, resume)) {
            val layout = listenLayout(w, h, state)
            val needed = listenContentHeightDp(layout, state, fontScale)
            assertTrue(needed <= h, "$size ${state::class.simpleName} $w x $h at font scale $fontScale needs $needed dp")
        }
    }

    @Test
    fun theButtonsFitTheirRowAndKeepTheirTargets() {
        for ((size, list) in boxes) for ((w, h) in list) for (state in listOf(controls, resume)) {
            val layout = listenLayout(w, h, state)
            val row = listenButtonsWidthDp(layout)
            val room = listenButtonsRoomDp(layout, w)
            assertTrue(row <= room, "$size $w x $h buttons need $row dp of $room")
            assertTrue(layout.playButtonDp >= 40f && (!layout.showSkips || layout.sideButtonDp >= 40f), "$size touch targets")
        }
        // Where the widget is tall enough, the play button is a full 48 dp target.
        assertEquals(48f, listenLayout(LISTEN_WIDE_MIN_WIDTH_DP, LISTEN_TALL_MIN_HEIGHT_DP, controls).playButtonDp)
        assertEquals(48f, listenLayout(LISTEN_SQUARE_MIN_WIDTH_DP, LISTEN_TALL_MIN_HEIGHT_DP, controls).playButtonDp)
    }

    // -- Now Playing and Continue Listening ----------------------------------------------------

    @Test
    fun nowPlayingShowsItsCoverOnlyWhereItFits() {
        assertFalse(nowPlayingShowsCover(180f))
        assertTrue(nowPlayingShowsCover(NOW_PLAYING_COVER_MIN_DP))
        for (width in listOf(NOW_PLAYING_WIDE_MIN_DP, 220f, NOW_PLAYING_COVER_MIN_DP, 320f)) {
            val cover = if (nowPlayingShowsCover(width)) WidgetDimens.ROW_COVER_DP + WidgetDimens.GAP_DP else 0f
            val used = 2 * WidgetDimens.ROW_PADDING_DP + cover + 2 * WidgetDimens.SIDE_BUTTON_DP + WidgetDimens.ROW_PLAY_DP
            assertTrue(width - used >= WidgetDimens.ROW_TEXT_MIN_DP, "Now Playing at $width dp leaves ${width - used} dp for the title")
        }
    }

    @Test
    fun continueListeningFitsItsSmallestSize() {
        val used = 2 * WidgetDimens.ROW_PADDING_DP + WidgetDimens.ROW_COVER_DP + WidgetDimens.GAP_DP + WidgetDimens.ROW_PLAY_DP
        assertTrue(CONTINUE_MIN_WIDTH_DP - used >= WidgetDimens.ROW_TEXT_MIN_DP)
    }

    // -- Texts ---------------------------------------------------------------------------------

    @Test
    fun texts() {
        val resume = ListenWidgetState.Resume("a", "Dune", "Frank Herbert", 25, "6 h left")
        assertEquals("6 h left", listenMetaText(resume))
        assertEquals("Play Dune", listenPlayDescription(resume))

        val controls = ListenWidgetState.Controls("a", "Dune", "Frank Herbert", "Chapter 3", 40, isPlaying = true, canSkip = true, canJumpChapters = true)
        assertEquals("Chapter 3", listenSecondLine(controls))
        assertEquals("Frank Herbert", listenSecondLine(controls.copy(chapterTitle = null)))
        assertEquals("40%", listenMetaText(controls))
        assertEquals("Pause", listenPlayDescription(controls))
        assertEquals("Play", listenPlayDescription(controls.copy(isPlaying = false)))
        assertEquals("Frank Herbert", listenSecondLine(resume))
        assertNull(listenSecondLine(ListenWidgetState.Empty(everPlayed = false)))
        assertNull(listenMetaText(ListenWidgetState.Empty(everPlayed = false)))
        assertEquals("6 h left", listenBarSecondLine(resume))
        assertEquals("Chapter 3", listenBarSecondLine(controls))
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
        // 256 x 256 RGB_565 is 128 KiB; even counted once per cover layout it stays at 512 KiB per placed widget.
        assertTrue(widgetCoverBytes(256, 256) * LISTEN_COVER_LAYOUTS <= 512L * 1024)
        assertEquals(256, WIDGET_COVER_MAX_SIDE)
        assertEquals(131_072L, widgetCoverBytes(256, 256))
        assertEquals(256L * 170 * 2, widgetCoverBytes(256, 170))
    }
}
