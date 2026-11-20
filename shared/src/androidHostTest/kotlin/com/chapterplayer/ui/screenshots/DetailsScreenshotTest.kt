package com.chapterplayer.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.browse.AboutInfo
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.ui.details.BookDetailsContent
import com.chapterplayer.ui.details.BookDetailsUiState
import com.chapterplayer.ui.details.DetailsDownload
import com.chapterplayer.ui.details.DetailsDownloadPhase
import com.chapterplayer.ui.details.DetailsPhase
import com.chapterplayer.ui.details.DetailsPlayback
import com.chapterplayer.ui.details.DetailsTab
import com.chapterplayer.ui.details.DownloadActions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of Book details (review R2, R25): the chapters, about and series tabs, a long title, playing, finished,
 * downloading, cannot play offline, a play error, loading and not found; dark and light, at font scale 2.0, in landscape and
 * at a tablet width. Synthetic titles only. Record with recordRoborazziAndroidHostTest (docs/architecture/testing.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-mdpi")
class DetailsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = AudioBook(
        "b1", "A Reasonably Titled Book", "A. Writer", "N. Reader", 36_000_000L, emptyList(), "", null, ServerSource.ABS, "s1",
        seriesName = "The Saga #2",
    )
    private val longBook = book.copy(
        title = "An Extraordinarily Long Audiobook Title That Cannot Possibly Fit On One Line, Unabridged, Part One of Seven",
        author = "A Very Long Author Name, Another Equally Long Narrator Name",
    )
    private val chapters = listOf(
        ChapterRow(0, "Opening Credits", 0L, 60_000L, false),
        ChapterRow(1, "Chapter 1. The Beginning", 60_000L, 1_800_000L, true),
        ChapterRow(2, "Chapter 2. A Chapter Name That Is Far Too Long To Fit On One Line Beside Its Time", 1_860_000L, 2_400_000L, false),
        ChapterRow(3, "Chapter 3", 4_260_000L, 1_200_000L, false),
    )
    private val about = AboutInfo(
        description = "A synthetic description of the book. It runs over several lines so the About tab shows how long text wraps, " +
            "and a second sentence to make sure it does. ".repeat(3),
        genres = listOf("Science Fiction", "Adventure", "A Genre With A Rather Long Name"),
        year = "2021", seriesLine = "The Saga #2", authorLine = "A. Writer", narratorLine = "N. Reader", needsConnection = false,
    )

    private val loaded = BookDetailsUiState(
        phase = DetailsPhase.LOADED, book = book, chapters = chapters, about = about, timeLeft = "9 h 12 min left",
        lengthText = "10 h", detailsLoaded = true, canPlay = true, resumePositionMs = 900_000L, currentPositionMs = 900_000L,
    )

    @Test fun chaptersDark() = shot("details_chapters_dark", ThemeMode.DARK, 1.0f, loaded)
    @Test fun chaptersLight() = shot("details_chapters_light", ThemeMode.LIGHT, 1.0f, loaded)
    @Test @Config(qualifiers = "w360dp-h1300dp-mdpi") fun chaptersScale200() = shot("details_chapters_200", ThemeMode.DARK, 2.0f, loaded)
    @Test fun longTitle() = shot("details_long_title", ThemeMode.DARK, 1.0f, loaded.copy(book = longBook))
    @Test fun aboutTab() = shot("details_about", ThemeMode.DARK, 1.0f, loaded.copy(selectedTab = DetailsTab.ABOUT))
    @Test fun playing() = shot("details_playing", ThemeMode.DARK, 1.0f, loaded.copy(playback = DetailsPlayback.PLAYING))
    @Test fun finished() = shot("details_finished", ThemeMode.DARK, 1.0f, loaded.copy(finished = true, timeLeft = "Finished"))
    @Test fun downloading() = shot(
        "details_downloading", ThemeMode.DARK, 1.0f,
        loaded.copy(download = DetailsDownload(DetailsDownloadPhase.DOWNLOADING, 42, "42% · 3h 10m left")),
    )
    @Test fun cannotPlayOffline() = shot(
        "details_offline", ThemeMode.DARK, 1.0f,
        loaded.copy(canPlay = false, cannotPlayReason = "No network. Download the book to play it offline."),
    )
    @Test fun playError() = shot(
        "details_play_error", ThemeMode.DARK, 1.0f, loaded.copy(playError = "Couldn't reach the server. Check your connection and try again."),
    )
    @Test fun loading() = shot("details_loading", ThemeMode.DARK, 1.0f, BookDetailsUiState())
    @Test fun notFound() = shot("details_not_found", ThemeMode.DARK, 1.0f, BookDetailsUiState(phase = DetailsPhase.NOT_FOUND))
    @Test @Config(qualifiers = "w800dp-h360dp-mdpi") fun landscape() = shot("details_landscape", ThemeMode.DARK, 1.0f, loaded)
    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun tablet() = shot("details_tablet", ThemeMode.DARK, 1.0f, loaded)

    private fun shot(name: String, theme: ThemeMode, fontScale: Float, state: BookDetailsUiState) {
        compose.setContent { ScreenshotFrame(theme, fontScale) { Details(state) } }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }

    @Composable
    private fun Details(state: BookDetailsUiState) = BookDetailsContent(
        state = state, onBack = {}, onRetry = {}, onPlayClick = {}, onDismissPlayError = {}, onChapterClick = {},
        onSelectTab = {}, onOpenBook = {}, onOpenGroup = { _, _ -> }, onOpenPlayer = {},
        downloadActions = DownloadActions({}, {}, {}, {}, {}, {}, {}),
    )
}
