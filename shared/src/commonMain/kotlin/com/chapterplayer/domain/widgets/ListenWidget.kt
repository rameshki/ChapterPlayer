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
    /** One row: play/pause and the title. */
    COMPACT,

    /** Low (about 110 to 150 dp): one row of the title and chapter beside back 15 / play-pause / forward 30. */
    SHORT,

    /** Title, chapter, progress and the full controls row below. */
    STANDARD,

    /** As standard, with the cover. */
    EXPANDED,
}

/** Narrower than this (about three cells) the widget is one row: play/pause and the title. */
const val LISTEN_WIDE_MIN_DP = 220f

/** From this width every control is a 48 dp target (see [listenControlsMetrics]). */
const val LISTEN_ROOMY_MIN_DP = 300f

/** Lower than this (one cell, up to about 102 dp upright) the widget is one row too. */
const val LISTEN_SHORT_MIN_HEIGHT_DP = 110f

/** From this height the controls fit in their own row under the title, chapter and progress, also at font scale 1.3. */
const val LISTEN_STACKED_MIN_HEIGHT_DP = 150f

/** From this height (about three cells on a phone held upright) the widget shows the cover. */
const val LISTEN_EXPANDED_MIN_HEIGHT_DP = 250f

fun listenSize(widthDp: Float, heightDp: Float): ListenSize = when {
    widthDp < LISTEN_WIDE_MIN_DP || heightDp < LISTEN_SHORT_MIN_HEIGHT_DP -> ListenSize.COMPACT
    heightDp < LISTEN_STACKED_MIN_HEIGHT_DP -> ListenSize.SHORT
    heightDp < LISTEN_EXPANDED_MIN_HEIGHT_DP -> ListenSize.STANDARD
    else -> ListenSize.EXPANDED
}

/** What a layout draws besides the title and the play button (which every size has). */
data class ListenLayout(
    val showCover: Boolean,
    /** The author, or the chapter while a book is loaded. */
    val showSecondLine: Boolean,
    /** The progress bar and its percent text. */
    val showProgress: Boolean,
    val showSkips: Boolean,
    val showChapterButtons: Boolean,
    /** The controls sit in the title's row (the low size) instead of a row of their own. */
    val controlsBesideTitle: Boolean,
)

/**
 * The cover only in [ListenSize.EXPANDED]; buttons only for a loaded book that allows them; compact is one row. The
 * low size puts a loaded book's title and chapter beside its three main buttons (no progress row, no chapter
 * buttons), so nothing is clipped; the Resume layout fits there as it is and draws as the standard one.
 */
fun listenLayout(size: ListenSize, state: ListenWidgetState): ListenLayout {
    val controls = state as? ListenWidgetState.Controls
    val effective = if (size == ListenSize.SHORT && controls == null) ListenSize.STANDARD else size
    val roomy = effective != ListenSize.COMPACT
    val oneRow = effective == ListenSize.SHORT
    val book = state !is ListenWidgetState.Empty
    return ListenLayout(
        showCover = effective == ListenSize.EXPANDED && book,
        showSecondLine = roomy && book,
        showProgress = roomy && book && !oneRow,
        showSkips = roomy && controls?.canSkip == true,
        showChapterButtons = roomy && !oneRow && controls?.canJumpChapters == true,
        controlsBesideTitle = oneRow,
    )
}

/** Sizes in the Listen layout, shared with the Glance code so the height check below measures what is drawn. */
object ListenDimens {
    const val VERTICAL_PADDING_DP = 10f
    const val TITLE_SP = 14f
    const val TITLE_EXPANDED_SP = 16f
    const val SMALL_SP = 12f
    const val PROGRESS_GAP_DP = 6f
    const val PROGRESS_BAR_DP = 4f
    const val CONTROLS_GAP_DP = 4f
    const val RESUME_PLAY_DP = 52f
    const val COVER_DP = 72f

    /** A text line's height per sp: Roboto's ascent plus descent with the font padding, rounded up. */
    const val LINE_HEIGHT_PER_SP = 1.33f
}

/**
 * The height [layout] needs for [state] at [fontScale], in dp (an estimate on the generous side). Tests check every
 * size's layout fits the lowest height of its size class at font scale 1.3.
 */
fun listenContentHeightDp(layout: ListenLayout, state: ListenWidgetState, fontScale: Float): Float {
    fun line(sp: Float) = sp * fontScale * ListenDimens.LINE_HEIGHT_PER_SP
    val expanded = layout.showCover
    var text = if (expanded) 2 * line(ListenDimens.TITLE_EXPANDED_SP) else line(ListenDimens.TITLE_SP)
    if (layout.showSecondLine) {
        text += line(ListenDimens.SMALL_SP)
        val authorToo = state is ListenWidgetState.Controls && state.chapterTitle != null && state.author.isNotEmpty()
        if (expanded && authorToo) text += line(ListenDimens.SMALL_SP)
    }
    val playDp = listenControlsMetrics(LISTEN_ROOMY_MIN_DP).playButtonDp // the taller of the two
    var top = text
    if (layout.showCover) top = maxOf(top, ListenDimens.COVER_DP)
    if (state is ListenWidgetState.Resume) top = maxOf(top, ListenDimens.RESUME_PLAY_DP)
    if (layout.controlsBesideTitle) top = maxOf(top, playDp)
    var height = 2 * ListenDimens.VERTICAL_PADDING_DP + top
    if (layout.showProgress) height += ListenDimens.PROGRESS_GAP_DP + maxOf(ListenDimens.PROGRESS_BAR_DP, line(ListenDimens.SMALL_SP))
    if (state is ListenWidgetState.Controls && !layout.controlsBesideTitle) height += ListenDimens.CONTROLS_GAP_DP + playDp
    return height
}

/** Button sizes and the side padding of the controls row at a width. */
data class ListenControlsMetrics(val sideButtonDp: Float, val playButtonDp: Float, val horizontalPaddingDp: Float)

/**
 * Five buttons in a row: from [LISTEN_ROOMY_MIN_DP] every one is 48 dp; narrower (down to [LISTEN_WIDE_MIN_DP]) 40 dp
 * with a 44 dp play button and 8 dp sides, so the row is at most 220 dp and never runs over.
 */
fun listenControlsMetrics(widthDp: Float): ListenControlsMetrics =
    if (widthDp >= LISTEN_ROOMY_MIN_DP) ListenControlsMetrics(48f, 48f, 12f) else ListenControlsMetrics(40f, 44f, 8f)

// -- Texts ------------------------------------------------------------------------------------

fun listenEmptyText(state: ListenWidgetState.Empty): String =
    if (state.everPlayed) "Nothing to resume now" else "Nothing played yet"

/** The line under the title: the chapter of a loaded book (else its author); the author of the Resume book. */
fun listenSecondLine(state: ListenWidgetState): String? = when (state) {
    is ListenWidgetState.Controls -> state.chapterTitle ?: state.author.takeIf { it.isNotEmpty() }
    is ListenWidgetState.Resume -> state.author.takeIf { it.isNotEmpty() }
    is ListenWidgetState.Empty -> null
}

/** Beside the progress bar: percent and time left to resume (as Continue Listening), the percent while loaded. */
fun listenProgressText(state: ListenWidgetState): String? = when (state) {
    is ListenWidgetState.Resume -> "${state.percent}% · ${state.timeLeftText}"
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
 * The longest side of the widget's cover bitmap. Drawn at 72 dp (216 px on a 3x screen), so 256 px is sharp enough.
 * Decoded as RGB_565 (covers are JPEGs, no alpha), a 256 x 256 cover is 128 KiB; the two expanded layouts (220 and
 * 300 dp wide) each carry it, 256 KiB per placed widget: far under the RemoteViews bitmap limit (screen width x
 * height x 4 x 1.5 bytes, about 15 MB on a 1080 x 2400 phone) and the 1 MB binder transaction.
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
