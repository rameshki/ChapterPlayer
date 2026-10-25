package com.chapterplayer.domain.widgets

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.isPlayable
import com.chapterplayer.domain.browse.resumeCandidate
import com.chapterplayer.domain.browse.resumeCopy

/** The title shown while a book plays that this phone does not know (yet), as the player screen does. */
const val NOW_PLAYING_FALLBACK_TITLE = "Now playing"

/**
 * What the Now Playing widget shows. It holds no position, so a playing book's ticks never change it:
 * the widget is redrawn only when this value changes.
 */
sealed interface NowPlayingWidgetState {
    /** Nothing is loaded in the player: the widget only opens the app. */
    data object Idle : NowPlayingWidgetState

    data class Book(
        val title: String,
        val author: String,
        val chapterTitle: String?,
        /** Offer Pause (not Play): playing, or buffering with playback asked for (as the player screen). */
        val isPlaying: Boolean,
        /** Back 15 / forward 30 make sense: the book's length is known. */
        val canSkip: Boolean,
    ) : NowPlayingWidgetState
}

/**
 * The Now Playing widget for the player's [state]. [book] is the repository's copy of the loaded book; a different
 * book (or none) leaves the title to [NOW_PLAYING_FALLBACK_TITLE], never another book's name.
 */
fun nowPlayingWidgetState(state: PlaybackState, book: AudioBook?): NowPlayingWidgetState {
    val bookId = state.bookId ?: return NowPlayingWidgetState.Idle
    val known = book?.takeIf { it.id == bookId }
    val chapter = state.chapterIndex?.let { state.chapters.getOrNull(it) }
    return NowPlayingWidgetState.Book(
        title = known?.title ?: NOW_PLAYING_FALLBACK_TITLE,
        author = known?.author.orEmpty(),
        chapterTitle = chapter?.title,
        isPlaying = playPauseCommand(state) == WidgetCommand.PAUSE,
        canSkip = canSkip(state),
    )
}

/** What the Continue Listening widget shows. */
sealed interface ContinueWidgetState {
    /** Nothing to resume (or nothing that can play now). */
    data object Empty : ContinueWidgetState

    data class Book(
        val bookId: String,
        val title: String,
        val author: String,
        /** Whole percent listened, 0 to 100. */
        val percent: Int,
        val timeLeftText: String,
    ) : ContinueWidgetState
}

/**
 * The Continue Listening widget: Home's Resume card ([resumeCandidate]) among the books that can play now
 * ([isPlayable]), so offline or with "Downloaded only" a book that is not on the phone is never offered.
 */
fun continueWidgetState(items: List<BrowseItem>, online: Boolean, downloadedOnly: Boolean): ContinueWidgetState {
    // A merged book is offered when any copy can play, and the widget resumes the copy played last if it can.
    val canPlay = { member: BrowseItem -> isPlayable(member.downloadState, online, downloadedOnly) }
    val playable = items.filter { entry -> entry.allSources.any(canPlay) }
    val resume = resumeCandidate(playable) ?: return ContinueWidgetState.Empty
    val book = resumeCopy(resume.entry, canPlay).book
    return ContinueWidgetState.Book(
        bookId = book.id,
        title = book.title,
        author = book.author,
        percent = (resume.fraction * 100).toInt().coerceIn(0, 100),
        timeLeftText = resume.timeLeft,
    )
}
