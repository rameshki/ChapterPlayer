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

/** The Listen widget's three layouts. */
enum class ListenSize { COMPACT, STANDARD, EXPANDED }

/** Narrower than this (about three cells) the widget is one row: play/pause and the title. */
const val LISTEN_WIDE_MIN_DP = 220f

/** Lower than this (one cell, up to about 102 dp upright) the widget is one row too. */
const val LISTEN_STANDARD_MIN_HEIGHT_DP = 110f

/** From this height (about three cells on a phone held upright) the widget shows the cover. */
const val LISTEN_EXPANDED_MIN_HEIGHT_DP = 250f

fun listenSize(widthDp: Float, heightDp: Float): ListenSize = when {
    widthDp < LISTEN_WIDE_MIN_DP || heightDp < LISTEN_STANDARD_MIN_HEIGHT_DP -> ListenSize.COMPACT
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
)

/** The cover only in [ListenSize.EXPANDED]; buttons only for a loaded book that allows them; compact is one row. */
fun listenLayout(size: ListenSize, state: ListenWidgetState): ListenLayout {
    val roomy = size != ListenSize.COMPACT
    val controls = state as? ListenWidgetState.Controls
    val book = state !is ListenWidgetState.Empty
    return ListenLayout(
        showCover = size == ListenSize.EXPANDED && book,
        showSecondLine = roomy && book,
        showProgress = roomy && book,
        showSkips = roomy && controls?.canSkip == true,
        showChapterButtons = roomy && controls?.canJumpChapters == true,
    )
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

/** Beside the progress bar: percent and time left to resume (as Continue Listening), the percent while loaded. */
fun listenProgressText(state: ListenWidgetState): String? = when (state) {
    is ListenWidgetState.Resume -> "${state.percent}% · ${state.timeLeftText}"
    is ListenWidgetState.Controls -> "${state.percent}%"
    is ListenWidgetState.Empty -> null
}

/** The play button's content description. */
fun listenPlayDescription(state: ListenWidgetState): String = when (state) {
    is ListenWidgetState.Resume -> "Play ${state.title}"
    is ListenWidgetState.Controls -> if (state.isPlaying) "Pause" else "Play"
    is ListenWidgetState.Empty -> "Open ChapterPlayer"
}

fun listenCoverDescription(state: ListenWidgetState): String? = when (state) {
    is ListenWidgetState.Resume -> "Cover of ${state.title}"
    is ListenWidgetState.Controls -> "Cover of ${state.title}"
    is ListenWidgetState.Empty -> null
}

// -- Cover ------------------------------------------------------------------------------------

/**
 * The longest side of the widget's cover bitmap. Drawn at about 72 dp (288 px at the densest screens), so 256 px is
 * sharp enough, and a 256 x 256 ARGB bitmap is 256 KiB: far under the RemoteViews bitmap limit (screen width x
 * height x 4 x 1.5 bytes, about 15 MB on a 1080 x 2400 phone).
 */
const val WIDGET_COVER_MAX_SIDE = 256

/** Bytes of an ARGB_8888 bitmap of [width] x [height]. */
fun widgetCoverBytes(width: Int, height: Int): Long = width.toLong() * height * 4
