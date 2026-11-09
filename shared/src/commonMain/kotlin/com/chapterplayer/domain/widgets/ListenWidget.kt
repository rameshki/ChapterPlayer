package com.chapterplayer.domain.widgets

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.playback.remoteCommandsEnabled

/*
 * The Listen widget: Resume and the playback controls in one. With nothing loaded in the player it offers the book
 * Continue Listening offers (the same rule, [continueWidgetState]) with a Play button; once a book is loaded (playing
 * or paused) it shows that book with the controls. What it shows is decided here; the Glance layout only draws it.
 */

/** What the Listen widget shows. It changes at most once per whole percent of the book, never on a position tick. */
sealed interface ListenWidgetState {
    /** Nothing to offer: nothing was ever played ([everPlayed] false), or nothing that was played can play now. */
    data class Empty(val everPlayed: Boolean) : ListenWidgetState

    /** Nothing is loaded: the Continue Listening book, started by Play through the widget Resume path. */
    data class Resume(
        val bookId: String,
        val title: String,
        val author: String,
        /** Whole percent listened, 0 to 100. */
        val percent: Int,
        val timeLeftText: String,
    ) : ListenWidgetState

    /** A book is loaded (playing or paused): its controls, acting on that book only. */
    data class Controls(
        val bookId: String,
        val title: String,
        val author: String,
        val chapterTitle: String?,
        /** Whole percent of the book played, 0 to 100 (the progress bar). */
        val percent: Int,
        /** Offer Pause (not Play). */
        val isPlaying: Boolean,
        /** Back 15 / forward 30 make sense: the length is known. */
        val canSkip: Boolean,
        /** Previous / next chapter: two chapters or more. */
        val canJumpChapters: Boolean,
    ) : ListenWidgetState
}

/**
 * The Listen widget for the player's [playback] and the library [items]. A loaded book shows the controls (its title
 * and buttons follow the Now Playing rules, [nowPlayingWidgetState]); otherwise the Continue Listening book
 * ([continueWidgetState], so both widgets always offer the same copy: a downloaded one first, never a question).
 */
fun listenWidgetState(
    playback: PlaybackState,
    loadedBook: AudioBook?,
    items: List<BrowseItem>,
    online: Boolean,
    downloadedOnly: Boolean,
): ListenWidgetState {
    val loaded = nowPlayingWidgetState(playback, loadedBook)
    if (loaded is NowPlayingWidgetState.Book) {
        return ListenWidgetState.Controls(
            bookId = playback.bookId!!,
            title = loaded.title,
            author = loaded.author,
            chapterTitle = loaded.chapterTitle,
            percent = wholePercent(playback.positionMs, playback.durationMs),
            isPlaying = loaded.isPlaying,
            canSkip = loaded.canSkip,
            canJumpChapters = remoteCommandsEnabled(playback).chapters,
        )
    }
    return when (val offered = continueWidgetState(items, online, downloadedOnly)) {
        is ContinueWidgetState.Book -> ListenWidgetState.Resume(offered.bookId, offered.title, offered.author, offered.percent, offered.timeLeftText)
        ContinueWidgetState.Empty -> ListenWidgetState.Empty(everPlayed = items.any { it.progress != null })
    }
}

private fun wholePercent(positionMs: Long, durationMs: Long): Int =
    if (durationMs <= 0) 0 else (positionMs.coerceAtLeast(0L) * 100 / durationMs).toInt().coerceIn(0, 100)

/** The book whose cover the widget may show; null when it shows none. */
val ListenWidgetState.coverBookId: String?
    get() = when (this) {
        is ListenWidgetState.Resume -> bookId
        is ListenWidgetState.Controls -> bookId
        is ListenWidgetState.Empty -> null
    }

// -- Sizes ------------------------------------------------------------------------------------

/** The Listen widget's layouts, from the size the launcher gives it. */
enum class ListenSize {
    /** Too low or narrow for a cover: play/pause and the title in one row. */
    MINI,

    /** A slim long bar (about 4x1): cover thumbnail, title and one line, the buttons on the right. */
    BAR,

    /** About 2x2: cover at the top left, the title, a round play button with the time left beside it. */
    SQUARE,

    /** About 4x2: cover at the left; title, author or chapter, progress, time left and the buttons beside it. */
    WIDE,

    /** About 4x3 or taller: a bigger cover, title, author and chapter, progress and the full control row. */
    LARGE,
}

/** From this width (three cells) the low widget is the bar with its cover. */
const val LISTEN_BAR_MIN_WIDTH_DP = 190f

/** From this height the bar has room for its cover and two lines at font scale 1.3 (a 4x1 upright is about 102 dp). */
const val LISTEN_BAR_MIN_HEIGHT_DP = 68f

/** From this height the widget is a square, wide or large card instead of a bar. */
const val LISTEN_TALL_MIN_HEIGHT_DP = 160f

/** The narrowest square. */
const val LISTEN_SQUARE_MIN_WIDTH_DP = 110f

/** From this width (three cells) a tall widget is wide (cover beside the text) instead of square. */
const val LISTEN_WIDE_MIN_WIDTH_DP = 200f

/** From this width the wide card's cover is the big one. */
const val LISTEN_WIDE_BIG_COVER_MIN_DP = 260f

/** From this width the large card's cover is the big one. */
const val LISTEN_LARGE_BIG_COVER_MIN_DP = 270f

/** From this height (and [LISTEN_WIDE_MIN_WIDTH_DP] wide) the widget is large. */
const val LISTEN_LARGE_MIN_HEIGHT_DP = 250f

/** One size Glance lays the widget out at ([LISTEN_BUCKETS]). */
data class ListenBucket(val name: String, val widthDp: Float, val heightDp: Float)

/**
 * The sizes the widget is laid out at (`SizeMode.Responsive`). Glance composes each one with `LocalSize` = the bucket,
 * never the launcher's real size, and the launcher shows the closest one that fits; so every width rule here is reached
 * only through a bucket. The roomy buckets exist so the buttons that fit at those widths really appear: the bar's
 * back 15 / forward 30 (340 dp), the wide card's skips (260) and chapter buttons (330), the large card's 48 dp row (270).
 */
val LISTEN_BUCKETS: List<ListenBucket> = listOf(
    ListenBucket("MINI", 110f, 40f),
    ListenBucket("MINI_TALL", 110f, 56f),
    ListenBucket("BAR", LISTEN_BAR_MIN_WIDTH_DP, LISTEN_BAR_MIN_HEIGHT_DP),
    ListenBucket("BAR_ROOMY", 340f, LISTEN_BAR_MIN_HEIGHT_DP),
    ListenBucket("SQUARE", LISTEN_SQUARE_MIN_WIDTH_DP, LISTEN_TALL_MIN_HEIGHT_DP),
    ListenBucket("WIDE", LISTEN_WIDE_MIN_WIDTH_DP, LISTEN_TALL_MIN_HEIGHT_DP),
    ListenBucket("WIDE_MID", LISTEN_WIDE_BIG_COVER_MIN_DP, LISTEN_TALL_MIN_HEIGHT_DP),
    ListenBucket("WIDE_ROOMY", 330f, LISTEN_TALL_MIN_HEIGHT_DP),
    ListenBucket("LARGE", LISTEN_WIDE_MIN_WIDTH_DP, LISTEN_LARGE_MIN_HEIGHT_DP),
    ListenBucket("LARGE_ROOMY", LISTEN_LARGE_BIG_COVER_MIN_DP, LISTEN_LARGE_MIN_HEIGHT_DP),
)

fun listenSize(widthDp: Float, heightDp: Float): ListenSize = when {
    heightDp < LISTEN_TALL_MIN_HEIGHT_DP ->
        if (widthDp >= LISTEN_BAR_MIN_WIDTH_DP && heightDp >= LISTEN_BAR_MIN_HEIGHT_DP) ListenSize.BAR else ListenSize.MINI
    widthDp < LISTEN_WIDE_MIN_WIDTH_DP -> ListenSize.SQUARE
    heightDp < LISTEN_LARGE_MIN_HEIGHT_DP -> ListenSize.WIDE
    else -> ListenSize.LARGE
}

/** What one Listen layout draws, in dp and sp; the Glance code draws exactly this. */
data class ListenLayout(
    val size: ListenSize,
    /** The rounded cover's side; 0 for none. */
    val coverDp: Float,
    val titleSp: Float,
    val titleLines: Int,
    /** The author (Resume) or the chapter (a loaded book) under the title. */
    val showSecondLine: Boolean,
    /** Large, a loaded book: the author and the chapter under the title. */
    val showAuthorAndChapter: Boolean,
    val showProgress: Boolean,
    /** "2h 34m left" (Resume) or the percent (a loaded book). */
    val showMeta: Boolean,
    val showSkips: Boolean,
    val showChapterButtons: Boolean,
    val sideButtonDp: Float,
    val playButtonDp: Float,
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
)

/** Sizes in the Listen layout (and its height check). */
object ListenDimens {
    const val PADDING_DP = 10f
    const val BAR_VERTICAL_PADDING_DP = 8f
    const val BAR_COVER_DP = 44f
    const val SQUARE_COVER_DP = 52f
    const val WIDE_COVER_DP = 92f
    const val WIDE_SMALL_COVER_DP = 72f
    const val LARGE_COVER_DP = 112f
    const val LARGE_SMALL_COVER_DP = 80f
    const val TITLE_SP = 15f
    const val TITLE_LARGE_SP = 17f
    const val SECOND_SP = 13f
    const val META_SP = 12f
    const val LINE_GAP_DP = 4f
    const val PROGRESS_GAP_DP = 6f
    const val PROGRESS_BAR_DP = 4f
    const val BUTTONS_GAP_DP = 6f

    /** The bar shows back 15 / forward 30 only when its title keeps at least this much. */
    const val BAR_TEXT_MIN_DP = 110f

    /** Back 15, forward 30 and the chapter buttons where there is room for full targets. */
    const val ROOMY_SIDE_BUTTON_DP = 48f

    /** A text line's height per sp: Roboto's ascent plus descent with the font padding, rounded up. */
    const val LINE_HEIGHT_PER_SP = 1.33f
}

/**
 * The layout for a [widthDp] x [heightDp] widget showing [state] (in production always one of [LISTEN_BUCKETS]).
 * Back 15 / forward 30 and the chapter buttons appear only for a loaded book that allows them and only where they fit
 * ([listenButtonsRoomDp]); more buttons win over bigger ones, and side buttons are 48 dp where that still fits, else 40.
 */
fun listenLayout(widthDp: Float, heightDp: Float, state: ListenWidgetState): ListenLayout {
    val size = listenSize(widthDp, heightDp)
    val controls = state as? ListenWidgetState.Controls
    val book = state !is ListenWidgetState.Empty
    val side = WidgetDimens.SIDE_BUTTON_DP
    val base = when (size) {
        ListenSize.MINI -> ListenLayout(
            size, coverDp = 0f, titleSp = ListenDimens.TITLE_SP, titleLines = 1, showSecondLine = false, showAuthorAndChapter = false,
            showProgress = false, showMeta = false, showSkips = false, showChapterButtons = false, sideButtonDp = side,
            playButtonDp = (heightDp - 8f).coerceIn(40f, WidgetDimens.PLAY_TARGET_DP), horizontalPaddingDp = 6f, verticalPaddingDp = 0f,
        )
        ListenSize.BAR -> ListenLayout(
            size, coverDp = ListenDimens.BAR_COVER_DP, titleSp = ListenDimens.TITLE_SP, titleLines = 1, showSecondLine = book,
            showAuthorAndChapter = false, showProgress = false, showMeta = false, showSkips = false, showChapterButtons = false,
            sideButtonDp = side, playButtonDp = WidgetDimens.PLAY_TARGET_DP, horizontalPaddingDp = ListenDimens.PADDING_DP,
            verticalPaddingDp = ListenDimens.BAR_VERTICAL_PADDING_DP,
        )
        ListenSize.SQUARE -> ListenLayout(
            size, coverDp = ListenDimens.SQUARE_COVER_DP, titleSp = ListenDimens.TITLE_SP, titleLines = 1, showSecondLine = false,
            showAuthorAndChapter = false, showProgress = false, showMeta = book, showSkips = false, showChapterButtons = false,
            sideButtonDp = side, playButtonDp = WidgetDimens.PLAY_TARGET_DP, horizontalPaddingDp = ListenDimens.PADDING_DP,
            verticalPaddingDp = ListenDimens.PADDING_DP,
        )
        ListenSize.WIDE -> ListenLayout(
            size, coverDp = if (widthDp >= LISTEN_WIDE_BIG_COVER_MIN_DP) ListenDimens.WIDE_COVER_DP else ListenDimens.WIDE_SMALL_COVER_DP,
            titleSp = ListenDimens.TITLE_SP, titleLines = 1, showSecondLine = book,
            showAuthorAndChapter = false, showProgress = book, showMeta = book, showSkips = false, showChapterButtons = false,
            sideButtonDp = side, playButtonDp = WidgetDimens.PLAY_TARGET_DP, horizontalPaddingDp = ListenDimens.PADDING_DP,
            verticalPaddingDp = ListenDimens.PADDING_DP,
        )
        ListenSize.LARGE -> ListenLayout(
            size, coverDp = if (widthDp >= LISTEN_LARGE_BIG_COVER_MIN_DP) ListenDimens.LARGE_COVER_DP else ListenDimens.LARGE_SMALL_COVER_DP,
            titleSp = ListenDimens.TITLE_LARGE_SP, titleLines = 2, showSecondLine = book,
            showAuthorAndChapter = controls?.chapterTitle != null && controls.author.isNotEmpty(), showProgress = book, showMeta = book,
            showSkips = false, showChapterButtons = false, sideButtonDp = side, playButtonDp = WidgetDimens.PLAY_TARGET_DP,
            horizontalPaddingDp = ListenDimens.PADDING_DP, verticalPaddingDp = ListenDimens.PADDING_DP,
        )
    }
    if (!book) return base.copy(coverDp = 0f)
    if (controls == null || size == ListenSize.MINI || size == ListenSize.SQUARE) return base
    if (!controls.canSkip) return base
    val room = listenButtonsRoomDp(base, widthDp)
    val sides = listOf(ListenDimens.ROOMY_SIDE_BUTTON_DP, side)
    if (controls.canJumpChapters && size != ListenSize.BAR) {
        sides.firstOrNull { base.playButtonDp + 4 * it <= room }?.let { return base.copy(showSkips = true, showChapterButtons = true, sideButtonDp = it) }
    }
    val skipSide = sides.firstOrNull { base.playButtonDp + 2 * it <= room } ?: return base
    return base.copy(showSkips = true, sideButtonDp = skipSide)
}

/** The width the buttons of [layout] take. */
fun listenButtonsWidthDp(layout: ListenLayout): Float {
    var width = layout.playButtonDp
    if (layout.showSkips) width += 2 * layout.sideButtonDp
    if (layout.showChapterButtons) width += 2 * layout.sideButtonDp
    return width
}

/**
 * The width the buttons may use in a [widthDp] wide widget: the whole row (mini, square, large), the text column
 * beside the cover (wide) or what the cover and the title leave (bar).
 */
fun listenButtonsRoomDp(layout: ListenLayout, widthDp: Float): Float {
    val inner = widthDp - 2 * layout.horizontalPaddingDp
    val coverAndGap = if (layout.coverDp > 0f) layout.coverDp + WidgetDimens.GAP_DP else 0f
    return when (layout.size) {
        ListenSize.MINI -> inner
        ListenSize.SQUARE, ListenSize.LARGE -> inner
        ListenSize.WIDE -> inner - coverAndGap
        ListenSize.BAR -> inner - coverAndGap - ListenDimens.BAR_TEXT_MIN_DP - ListenDimens.BUTTONS_GAP_DP
    }
}

/**
 * The height [layout] needs for [state] at [fontScale], in dp (an estimate on the generous side). Tests check every
 * size class fits its smallest box at font scale 1.3.
 */
fun listenContentHeightDp(layout: ListenLayout, state: ListenWidgetState, fontScale: Float): Float {
    fun line(sp: Float) = sp * fontScale * ListenDimens.LINE_HEIGHT_PER_SP
    val title = layout.titleLines * line(layout.titleSp)
    val second = if (layout.showSecondLine) line(ListenDimens.SECOND_SP) * (if (layout.showAuthorAndChapter) 2 else 1) else 0f
    val progress = if (layout.showProgress) ListenDimens.PROGRESS_GAP_DP + ListenDimens.PROGRESS_BAR_DP else 0f
    val meta = if (layout.showMeta) line(ListenDimens.META_SP) else 0f
    val play = layout.playButtonDp
    val content = when (layout.size) {
        ListenSize.MINI -> maxOf(play, title)
        ListenSize.BAR -> maxOf(layout.coverDp, title + second, play)
        // Cover, title, then the play button with the time left beside it.
        ListenSize.SQUARE -> layout.coverDp + ListenDimens.PROGRESS_GAP_DP + title + ListenDimens.LINE_GAP_DP + maxOf(play, meta)
        // Beside the cover: title, second line, progress, time left, then the buttons.
        ListenSize.WIDE -> maxOf(layout.coverDp, title + second + progress + ListenDimens.LINE_GAP_DP + meta + ListenDimens.BUTTONS_GAP_DP + play)
        // Cover and text, then the progress with the time left beside it, then the control row.
        ListenSize.LARGE -> maxOf(layout.coverDp, title + second) + ListenDimens.PROGRESS_GAP_DP +
            maxOf(ListenDimens.PROGRESS_BAR_DP, meta) + ListenDimens.BUTTONS_GAP_DP + play
    }
    if (state is ListenWidgetState.Empty) return 2 * layout.verticalPaddingDp + maxOf(line(ListenDimens.TITLE_SP), 32f)
    return 2 * layout.verticalPaddingDp + content
}

// -- Texts ------------------------------------------------------------------------------------

fun listenEmptyText(state: ListenWidgetState.Empty): String =
    if (state.everPlayed) "Nothing to resume now" else "Nothing played yet"

/** The line under the title: the chapter of a loaded book (else its author); the author of the Resume book. */
fun listenSecondLine(state: ListenWidgetState): String? = when (state) {
    is ListenWidgetState.Controls -> state.chapterTitle ?: state.author.takeIf { it.isNotEmpty() }
    is ListenWidgetState.Resume -> state.author.takeIf { it.isNotEmpty() }
    is ListenWidgetState.Empty -> null
}

/** The bar's line under the title: the time left to resume, the chapter (else author) of a loaded book. */
fun listenBarSecondLine(state: ListenWidgetState): String? = when (state) {
    is ListenWidgetState.Resume -> state.timeLeftText
    is ListenWidgetState.Controls -> listenSecondLine(state)
    is ListenWidgetState.Empty -> null
}

/** Under the progress bar or beside the play button: the time left to resume, the percent while loaded. */
fun listenMetaText(state: ListenWidgetState): String? = when (state) {
    is ListenWidgetState.Resume -> state.timeLeftText
    is ListenWidgetState.Controls -> "${state.percent}%"
    is ListenWidgetState.Empty -> null
}

/** The play button's content description. (The cover is decorative: the title beside it says what it is.) */
fun listenPlayDescription(state: ListenWidgetState): String = when (state) {
    is ListenWidgetState.Resume -> "Play ${state.title}"
    is ListenWidgetState.Controls -> if (state.isPlaying) "Pause" else "Play"
    is ListenWidgetState.Empty -> "Open ChapterPlayer"
}

// -- Cover ------------------------------------------------------------------------------------

/**
 * The longest side of a widget cover bitmap. Drawn at up to 112 dp (336 px on a 3x screen; 256 px is sharp enough at
 * the usual sizes). Decoded as RGB_565 (covers are JPEGs, no alpha), a 256 x 256 cover is 128 KiB. Every Listen bucket
 * with a cover (eight of [LISTEN_BUCKETS]) draws the same Bitmap instance; RemoteViews keeps one bitmap cache for the
 * whole size map (and the portrait / landscape pair before Android 12) that matches bitmaps by identity, so it is
 * sent once: 128 KiB per placed widget, far under the 1 MB binder transaction and the RemoteViews bitmap limit
 * (screen width x height x 4 x 1.5 bytes, about 15 MB).
 */
const val WIDGET_COVER_MAX_SIDE = 256

/** Bytes of an RGB_565 bitmap of [width] x [height]. */
fun widgetCoverBytes(width: Int, height: Int): Long = width.toLong() * height * 2

/** What one cover fetch for the widget came to. */
enum class CoverFetch {
    /** Newly loaded: draw it. */
    LOADED,

    /** Already in the cache, or nothing to load (no covers on this platform). */
    CACHED,

    /** No cover now: none on the server, offline, refused, or the loader's 60 s backoff after a failure. */
    FAILED,
}

/** What the refresher does after a fetch. */
sealed interface CoverStep {
    data object Redraw : CoverStep
    data object Done : CoverStep
    data class RetryAfter(val delayMs: Long) : CoverStep
}

/** Longer than the cover loader's 60 s backoff, so the retry really asks again. */
const val COVER_RETRY_MS = 65_000L

/** A book whose cover failed this often in a row is left on the placeholder until its state changes. */
const val COVER_MAX_ATTEMPTS = 3

/**
 * After a fetch: a new cover is drawn once; a cached one needs nothing; a failure is tried again after the loader's
 * backoff while online (at most [COVER_MAX_ATTEMPTS] tries), and offline not at all (the network's return asks again).
 */
fun coverNextStep(result: CoverFetch, online: Boolean, attempts: Int): CoverStep = when (result) {
    CoverFetch.LOADED -> CoverStep.Redraw
    CoverFetch.CACHED -> CoverStep.Done
    CoverFetch.FAILED -> if (online && attempts < COVER_MAX_ATTEMPTS) CoverStep.RetryAfter(COVER_RETRY_MS) else CoverStep.Done
}
