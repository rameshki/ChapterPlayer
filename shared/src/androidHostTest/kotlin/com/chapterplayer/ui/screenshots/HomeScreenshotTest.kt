package com.chapterplayer.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.ResumeInfo
import com.chapterplayer.ui.home.HomeBody
import com.chapterplayer.ui.home.HomeUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of Home's body (review R2, R25): the Resume card with the shelves, a long title, the empty states
 * (no server, syncing, filters hiding everything) and a tablet width; dark and light and at font scale 2.0.
 * Synthetic titles only; covers are the app's placeholders. Record with recordRoborazziAndroidHostTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-mdpi")
class HomeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun book(id: String, title: String, series: String? = null) = AudioBook(
        id = id, title = title, author = "A. Writer", narrator = "N. Reader", durationMs = 36_000_000L, chapters = emptyList(),
        streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1", seriesName = series,
    )

    private fun item(id: String, title: String, fraction: Float? = null, series: String? = null, state: DownloadState = DownloadState.NOT_DOWNLOADED) =
        BrowseItem(
            book(id, title, series),
            fraction?.let { ReadingProgress(id, (36_000_000L * it).toLong(), 36_000_000L, 1_000L) },
            state,
        )

    private val resumeItem = item("r", "The Book You Were Listening To", 0.42f)
    private val resumeLong = item("rl", "An Extraordinarily Long Audiobook Title That Cannot Possibly Fit On One Line, Unabridged", 0.42f)

    private val full = HomeUiState(
        resume = ResumeInfo(resumeItem, "Chapter 7. The Turn", 0.42f, "5 h 48 min left"),
        upNext = listOf(item("u1", "Next Up One"), item("u2", "Next Up Two")),
        continueSeries = listOf(item("c1", "Series Book Four", 1f, "Saga #4"), item("c2", "Series Book Nine", 1f, "Other Saga #9")),
        inProgress = listOf(item("p1", "Half Done", 0.5f), item("p2", "Just Started", 0.05f), item("p3", "Nearly There", 0.9f)),
        recentlyAdded = listOf(item("a1", "Brand New One"), item("a2", "Brand New Two", state = DownloadState.DOWNLOADED), item("a3", "Brand New Three")),
        favourites = listOf(item("f1", "A Favourite")),
        favouriteIds = setOf("f1"),
        hasServers = true, hasBooks = true, isLoading = false,
    )

    @Test fun shelvesDark() = shot("home_shelves_dark", ThemeMode.DARK, 1.0f, full)
    @Test fun shelvesLight() = shot("home_shelves_light", ThemeMode.LIGHT, 1.0f, full)
    @Test @Config(qualifiers = "w360dp-h1400dp-mdpi") fun shelvesScale200() = shot("home_shelves_200", ThemeMode.DARK, 2.0f, full)
    @Test fun longResumeTitle() = shot("home_long_resume", ThemeMode.DARK, 1.0f, full.copy(resume = ResumeInfo(resumeLong, "Chapter 12. A Chapter Name That Is Also Far Too Long To Fit", 0.42f, "5 h 48 min left")))
    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun tablet() = shot("home_tablet", ThemeMode.DARK, 1.0f, full)
    @Test fun noServer() = shot("home_no_server", ThemeMode.DARK, 1.0f, HomeUiState(isLoading = false))
    @Test fun noServerLight() = shot("home_no_server_light", ThemeMode.LIGHT, 1.0f, HomeUiState(isLoading = false))
    @Test @Config(qualifiers = "w360dp-h1800dp-mdpi") fun noServerScale200() = shot("home_no_server_200", ThemeMode.DARK, 2.0f, HomeUiState(isLoading = false))
    @Test fun syncing() = shot("home_syncing", ThemeMode.DARK, 1.0f, HomeUiState(hasServers = true, isLoading = false, isSyncing = true))
    @Test fun filtersHiding() = shot("home_filters_hiding", ThemeMode.DARK, 1.0f, HomeUiState(hasServers = true, hasBooks = true, isLoading = false))

    private fun shot(name: String, theme: ThemeMode, fontScale: Float, state: HomeUiState) {
        compose.setContent {
            ScreenshotFrame(theme, fontScale, padded = false) { Body(state) }
        }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }

    @Composable
    private fun Body(state: HomeUiState) = HomeBody(
        state = state, onOpenServers = {}, onBookClick = {}, onResume = {}, onBookLongClick = {}, onFiltersCleared = {},
    )
}
