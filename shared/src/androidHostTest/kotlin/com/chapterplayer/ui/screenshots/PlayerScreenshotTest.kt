package com.chapterplayer.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.ui.player.PlayerContent
import com.chapterplayer.domain.playback.PlayerEmptyView
import com.chapterplayer.ui.player.PlayerMenuActions
import com.chapterplayer.ui.player.PlayerMenuState
import com.chapterplayer.ui.player.PlayerUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of the full player (review R2, R25): playing with chapters and a sleep timer, a very long title and
 * chapter name, a paused book with an error, and a failed start; dark and light, at font scale 2.0, in landscape and at
 * a 10 inch tablet width. Synthetic titles only. Record with recordRoborazziAndroidHostTest (docs/architecture/testing.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-mdpi")
class PlayerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val chapters = listOf(
        ChapterRow(0, "Opening Credits", 0L, 60_000L, false),
        ChapterRow(1, "Chapter 1. The Beginning", 60_000L, 1_800_000L, true),
        ChapterRow(2, "Chapter 2. The Middle", 1_860_000L, 2_400_000L, false),
    )

    private val playing = PlayerUiState(
        nothingPlaying = false, title = "A Reasonably Titled Book", author = "A. Writer, B. Reader",
        chapterTitle = "Chapter 1. The Beginning", positionMs = 900_000L, bookFraction = 0.25f, durationMs = 3_600_000L,
        chapterFraction = 0.5f, elapsed = "15:00", remaining = "45:00", chapterElapsed = "14:00", chapterRemaining = "16:00",
        timeLeft = "45 min left", isPlaying = true, showPause = true, speed = 1.25f, speedLabel = "1.25x",
        sleepTimer = SleepTimer.After(30), sleepLabel = "Sleep in 29 min", chapters = chapters, currentChapterIndex = 1,
        canGoPrevious = true, canGoNext = true,
    )

    private val longTitles = playing.copy(
        title = "An Extraordinarily Long Audiobook Title That Cannot Possibly Fit On One Line, Unabridged, Part One of Seven",
        author = "A Very Long Author Name, Another Equally Long Narrator Name, And A Third Contributor Name",
        chapterTitle = "Chapter 12. A Chapter Name That Is Also Far Too Long To Fit Beside The Controls Of The Player",
        isPlaying = false, showPause = false, sleepTimer = SleepTimer.Off, sleepLabel = "Sleep timer",
        error = "Couldn't reach the server. Playback will resume when it is back.",
    )

    private val failedStart = PlayerUiState(
        nothingPlaying = true, emptyView = PlayerEmptyView.FAILED, startError = "This book can't be played right now. Check the connection to its server.",
    )

    @Test fun playingDark() = shot("player_playing_dark", ThemeMode.DARK, 1.0f, playing)
    @Test fun playingLight() = shot("player_playing_light", ThemeMode.LIGHT, 1.0f, playing)
    @Test @Config(qualifiers = "w360dp-h1100dp-mdpi") fun playingScale200() = shot("player_playing_200", ThemeMode.DARK, 2.0f, playing)
    @Test fun longTitlesDark() = shot("player_long_titles_dark", ThemeMode.DARK, 1.0f, longTitles)
    @Test @Config(qualifiers = "w360dp-h1100dp-mdpi") fun longTitlesScale200() = shot("player_long_titles_200", ThemeMode.DARK, 2.0f, longTitles)
    @Test @Config(qualifiers = "w800dp-h360dp-mdpi") fun landscapePhone() = shot("player_landscape", ThemeMode.DARK, 1.0f, playing)
    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun tablet() = shot("player_tablet", ThemeMode.DARK, 1.0f, playing)
    @Test fun failedStartDark() = shot("player_failed_start", ThemeMode.DARK, 1.0f, failedStart)

    private fun shot(name: String, theme: ThemeMode, fontScale: Float, state: PlayerUiState) {
        compose.setContent {
            ScreenshotFrame(theme, fontScale, padded = false) { Player(state) }
        }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }

    @Composable
    private fun Player(state: PlayerUiState) = PlayerContent(
        state = state, bookmarks = emptyList(), history = emptyList(), menu = PlayerMenuState(),
        menuActions = PlayerMenuActions({}, { _, _ -> }, {}, {}, {}, {}, {}, {}),
        onBack = {}, onPlayPause = {}, onSkipBack = {}, onSkipForward = {}, onPreviousChapter = {}, onNextChapter = {},
        onSeekBook = {}, onSeekChapter = {}, onJumpToChapter = {}, onSetSpeed = {}, onSetSleepTimer = {},
        onAddBookmark = { _, _ -> }, onRenameBookmark = { _, _, _ -> }, onDeleteBookmark = { _, _ -> }, onJumpTo = {},
        onDismissError = {}, upNextSheet = {},
    )
}
