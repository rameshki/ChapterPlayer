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

    /** Glance only ever lays the widget out at these sizes (LocalSize is the bucket, not the launcher's size). */
    private fun bucket(name: String) = LISTEN_BUCKETS.single { it.name == name }

    private fun layoutAt(name: String, state: ListenWidgetState = controls) = bucket(name).let { listenLayout(it.widthDp, it.heightDp, state) }

    /**
     * The bucket the launcher shows for a real [w] x [h] widget, as Android 12+ picks it (written here apart from the
     * production code): the closest bucket that fits, by squared distance; the smallest when none fits.
     */
    private fun shown(w: Float, h: Float): ListenBucket {
        val fitting = LISTEN_BUCKETS.filter { it.widthDp <= w && it.heightDp <= h }
        if (fitting.isEmpty()) return LISTEN_BUCKETS.minBy { it.widthDp * it.heightDp }
        return fitting.minBy { (w - it.widthDp) * (w - it.widthDp) + (h - it.heightDp) * (h - it.heightDp) }
    }

    @Test
    fun eachBucketIsItsOwnSizeClass() {
        val expected = mapOf(
            "MINI" to ListenSize.MINI, "MINI_TALL" to ListenSize.MINI,
            "BAR" to ListenSize.BAR, "BAR_ROOMY" to ListenSize.BAR,
            "SQUARE" to ListenSize.SQUARE,
            "WIDE" to ListenSize.WIDE, "WIDE_MID" to ListenSize.WIDE, "WIDE_ROOMY" to ListenSize.WIDE,
            "LARGE" to ListenSize.LARGE, "LARGE_ROOMY" to ListenSize.LARGE,
        )
        assertEquals(expected.keys, LISTEN_BUCKETS.map { it.name }.toSet())
        for (b in LISTEN_BUCKETS) assertEquals(expected[b.name], listenSize(b.widthDp, b.heightDp), b.name)
    }

    @Test
    fun phonePlacementsGetASensibleLayout() {
        // Android 12+ handset cells: upright widths 130 / 203 / 276 dp, heights 102 / 220 / 337; sideways heights 51 / 117 / 184.
        val placements = mapOf(
            "2x1" to (130f to 102f), "3x1" to (203f to 102f), "4x1" to (276f to 102f),
            "2x2" to (130f to 220f), "3x2" to (203f to 220f), "4x2" to (276f to 220f),
            "3x3" to (203f to 337f), "4x3" to (276f to 337f),
            "4x1 sideways" to (554f to 51f), "4x2 sideways" to (554f to 117f), "4x3 sideways" to (554f to 184f),
        )
        val expected = mapOf(
            "2x1" to "MINI_TALL", "3x1" to "BAR", "4x1" to "BAR",
            "2x2" to "SQUARE", "3x2" to "WIDE", "4x2" to "WIDE_MID",
            "3x3" to "LARGE", "4x3" to "LARGE_ROOMY",
            "4x1 sideways" to "MINI", "4x2 sideways" to "BAR_ROOMY", "4x3 sideways" to "WIDE_ROOMY",
        )
        for ((name, size) in placements) assertEquals(expected[name], shown(size.first, size.second).name, name)

        // What a loaded book gets there: the default 4x2 has back 15 / forward 30, the 4x3 every button at 48 dp.
        val fourByTwo = layoutAt("WIDE_MID")
        assertTrue(fourByTwo.showSkips)
        assertFalse(fourByTwo.showChapterButtons)
        val fourByThree = layoutAt("LARGE_ROOMY")
        assertTrue(fourByThree.showChapterButtons)
        assertEquals(48f, fourByThree.sideButtonDp)
        // A 2x1 has a full 48 dp play button; the lowest mini bar keeps 40.
        assertEquals(48f, layoutAt("MINI_TALL").playButtonDp)
        assertEquals(40f, layoutAt("MINI").playButtonDp)
    }

    @Test
    fun theCoverIsInEverySizeButTheMiniBar() {
        for (b in LISTEN_BUCKETS) for (state in listOf(controls, resume)) {
            val layout = listenLayout(b.widthDp, b.heightDp, state)
            if (layout.size == ListenSize.MINI) assertEquals(0f, layout.coverDp, b.name) else assertTrue(layout.coverDp > 0f, b.name)
        }
        assertEquals(44f, layoutAt("BAR").coverDp)
        assertEquals(52f, layoutAt("SQUARE").coverDp)
        assertEquals(72f, layoutAt("WIDE").coverDp)
        assertEquals(92f, layoutAt("WIDE_MID").coverDp)
        assertEquals(80f, layoutAt("LARGE").coverDp) // three cells wide: the title keeps room beside it
        assertEquals(112f, layoutAt("LARGE_ROOMY").coverDp)
        val mini = bucket("MINI")
        assertEquals(0f, listenLayout(mini.widthDp, mini.heightDp, ListenWidgetState.Empty(everPlayed = true)).coverDp)
    }

    /**
     * Which buttons each bucket shows for a loaded book that allows them all, worked by hand from the widths
     * (padding 10 + 10, gap 8 after the cover, 6 before the bar's buttons, play 48, side buttons 40 or 48):
     * BAR 190: 190 - 20 - 44 - 8 - 6 - 48 = 64 dp of title: play only (back / forward would leave under 110).
     * BAR_ROOMY 340: 340 - 20 - 44 - 8 - 6 - (48 + 2 x 48) = 118 dp of title: back / forward at 48 dp.
     * WIDE 200: 200 - 20 - 72 - 8 = 100 dp beside the cover: play only (back / forward need 128).
     * WIDE_MID 260: 260 - 20 - 92 - 8 = 140: back / forward at 40 (48 would need 144).
     * WIDE_ROOMY 330: 330 - 20 - 92 - 8 = 210: all five at 40 (208).
     * LARGE 200: 200 - 20 = 180: back / forward at 48 (144; five at 40 would need 208).
     * LARGE_ROOMY 270: 270 - 20 = 250: all five at 48 (240).
     */
    @Test
    fun theButtonsEachBucketShows() {
        data class Buttons(val skips: Boolean, val chapters: Boolean, val side: Float)
        val expected = mapOf(
            "BAR" to Buttons(false, false, 40f),
            "BAR_ROOMY" to Buttons(true, false, 48f),
            "WIDE" to Buttons(false, false, 40f),
            "WIDE_MID" to Buttons(true, false, 40f),
            "WIDE_ROOMY" to Buttons(true, true, 40f),
            "LARGE" to Buttons(true, false, 48f),
            "LARGE_ROOMY" to Buttons(true, true, 48f),
            "SQUARE" to Buttons(false, false, 40f),
            "MINI" to Buttons(false, false, 40f),
            "MINI_TALL" to Buttons(false, false, 40f),
        )
        for ((name, want) in expected) {
            val l = layoutAt(name)
            assertEquals(want, Buttons(l.showSkips, l.showChapterButtons, if (l.showSkips) l.sideButtonDp else 40f), name)
        }
        // Resume shows Play alone everywhere; a book without chapters or length shows no extra buttons.
        val bare = controls.copy(canSkip = false, canJumpChapters = false)
        for (b in LISTEN_BUCKETS) for (state in listOf(resume, bare)) {
            val l = listenLayout(b.widthDp, b.heightDp, state)
            assertFalse(l.showSkips || l.showChapterButtons, "${b.name} ${state::class.simpleName}")
        }
        // The bar's title keeps at least 110 dp whenever it shows back / forward.
        val roomyBar = layoutAt("BAR_ROOMY")
        val titleWidth = 340f - 20f - 44f - 8f - 6f - (48f + 2 * roomyBar.sideButtonDp)
        assertTrue(titleWidth >= 110f, "bar title $titleWidth dp")
    }

    @Test
    fun whatEachSizeShows() {
        val bar = layoutAt("BAR_ROOMY")
        assertFalse(bar.showProgress)
        assertTrue(bar.showSecondLine)

        val square = layoutAt("SQUARE", resume)
        assertTrue(square.showMeta) // "1 h left" beside the play button
        assertFalse(square.showProgress)

        val wide = layoutAt("WIDE_MID", resume)
        assertTrue(wide.showSecondLine && wide.showProgress && wide.showMeta)

        val large = layoutAt("LARGE_ROOMY")
        assertTrue(large.showProgress && large.showAuthorAndChapter)
        assertEquals(2, large.titleLines)

        val mini = layoutAt("MINI")
        assertFalse(mini.showSecondLine || mini.showProgress)
    }

    @Test
    fun everyBucketFitsItsHeightAtLargeText() {
        for (fontScale in listOf(1.0f, 1.3f)) for (b in LISTEN_BUCKETS) for (state in listOf(controls, resume)) {
            val layout = listenLayout(b.widthDp, b.heightDp, state)
            val needed = listenContentHeightDp(layout, state, fontScale)
            assertTrue(needed <= b.heightDp, "${b.name} ${state::class.simpleName} at font scale $fontScale needs $needed dp of ${b.heightDp}")
        }
    }

    @Test
    fun theButtonRowFitsTheWidthOfEveryBucket() {
        // Counted from the bucket width directly: what sits beside the buttons, plus the buttons.
        for (b in LISTEN_BUCKETS) {
            val l = listenLayout(b.widthDp, b.heightDp, controls)
            var row = 48f
            if (l.showSkips) row += 2 * l.sideButtonDp
            if (l.showChapterButtons) row += 2 * l.sideButtonDp
            val beside = when (l.size) {
                ListenSize.MINI -> 0f
                ListenSize.SQUARE, ListenSize.LARGE -> 0f
                ListenSize.WIDE -> l.coverDp + 8f
                ListenSize.BAR -> l.coverDp + 8f + 6f + (if (l.showSkips) 110f else 0f)
            }
            assertTrue(2 * l.horizontalPaddingDp + beside + row <= b.widthDp, "${b.name}: ${2 * l.horizontalPaddingDp + beside + row} dp")
        }
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
        // 256 x 256 RGB_565 is 128 KiB, sent once per placed widget (every bucket draws the same bitmap).
        assertEquals(8, LISTEN_BUCKETS.count { listenSize(it.widthDp, it.heightDp) != ListenSize.MINI })
        assertEquals(256, WIDGET_COVER_MAX_SIDE)
        assertEquals(131_072L, widgetCoverBytes(256, 256))
        assertEquals(256L * 170 * 2, widgetCoverBytes(256, 170))
    }
}
