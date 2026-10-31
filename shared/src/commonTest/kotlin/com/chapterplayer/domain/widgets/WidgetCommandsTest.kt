package com.chapterplayer.domain.widgets

import com.chapterplayer.data.playback.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetCommandsTest {

    @Test
    fun nothingLoadedDoesNothing() {
        // A widget drawn before the app was gone must never start a book of its own.
        assertEquals(WidgetCommand.NONE, playPauseCommand(PlaybackState()))
        assertEquals(WidgetCommand.NONE, playPauseCommand(PlaybackState(isPlaying = true)))
    }

    @Test
    fun playingPauses() {
        assertEquals(WidgetCommand.PAUSE, playPauseCommand(PlaybackState(bookId = "a", isPlaying = true)))
        assertEquals(WidgetCommand.PAUSE, playPauseCommand(PlaybackState(bookId = "a", isBuffering = true, playWhenReady = true)))
    }

    @Test
    fun pausedPlays() {
        assertEquals(WidgetCommand.PLAY, playPauseCommand(PlaybackState(bookId = "a")))
        assertEquals(WidgetCommand.PLAY, playPauseCommand(PlaybackState(bookId = "a", isBuffering = true, playWhenReady = false)))
        // After an error Play is the way to try again.
        assertEquals(WidgetCommand.PLAY, playPauseCommand(PlaybackState(bookId = "a", error = "Playback stopped")))
    }

    @Test
    fun skipsNeedALoadedBookWithALength() {
        assertFalse(canSkip(PlaybackState()))
        assertFalse(canSkip(PlaybackState(bookId = "a", durationMs = 0)))
        assertTrue(canSkip(PlaybackState(bookId = "a", durationMs = 1_000)))
    }

    @Test
    fun narrowWidgetsShowPlayPauseOnly() {
        assertFalse(nowPlayingIsWide(NOW_PLAYING_WIDE_MIN_DP - 1f))
        assertTrue(nowPlayingIsWide(NOW_PLAYING_WIDE_MIN_DP))
        assertTrue(nowPlayingIsWide(400f))
    }
}
