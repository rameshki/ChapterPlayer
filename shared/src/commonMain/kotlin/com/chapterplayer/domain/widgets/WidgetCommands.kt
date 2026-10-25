package com.chapterplayer.domain.widgets

import com.chapterplayer.data.playback.PlaybackState

/** What a widget's play/pause button does now. */
enum class WidgetCommand { PLAY, PAUSE, NONE }

/**
 * Play/pause from a widget acts on the book in the player only. With nothing loaded (the app was gone and the
 * widget still shows the old book) it does nothing: a widget never starts a book of its own.
 */
fun playPauseCommand(state: PlaybackState): WidgetCommand = when {
    state.bookId == null -> WidgetCommand.NONE
    state.isPlaying || (state.isBuffering && state.playWhenReady) -> WidgetCommand.PAUSE
    else -> WidgetCommand.PLAY
}

/** Back 15 / forward 30 from a widget: only with a loaded book whose length is known. */
fun canSkip(state: PlaybackState): Boolean = state.bookId != null && state.durationMs > 0

/** From this width the Now Playing widget shows the title and the skip buttons; narrower it is play/pause only. */
const val NOW_PLAYING_WIDE_MIN_DP = 180f

fun nowPlayingIsWide(widthDp: Float): Boolean = widthDp >= NOW_PLAYING_WIDE_MIN_DP
