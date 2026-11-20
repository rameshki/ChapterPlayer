package com.chapterplayer.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.browse.BookGroup
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.ui.library.BookLayout
import com.chapterplayer.ui.library.GroupList
import com.chapterplayer.ui.library.SignInNeededState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of the Library's lists (review R2, R25): the large grid, the small grid and the list with long titles, a
 * favourite, a downloaded book, a dimmed one and one in progress; selection mode; the author groups; dark and light, at font
 * scale 2.0, in landscape and at a tablet width. Synthetic titles only. Record with recordRoborazziAndroidHostTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-mdpi")
class LibraryScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(id: String, title: String, progress: Float? = null, state: DownloadState = DownloadState.NOT_DOWNLOADED, author: String = "A. Writer") =
        BrowseItem(
            AudioBook(id, title, author, "N. Reader", 36_000_000L, emptyList(), "", null, ServerSource.ABS, "s1"),
            progress?.let { ReadingProgress(id, (36_000_000L * it).toLong(), 36_000_000L, 1_000L, isFinished = it >= 1f) },
            state,
        )

    private val items = listOf(
        item("1", "A Short Title"),
        item("2", "An Extraordinarily Long Audiobook Title That Cannot Possibly Fit On One Line, Unabridged", 0.4f),
        item("3", "Downloaded Book", state = DownloadState.DOWNLOADED),
        item("4", "Finished Book", 1f),
        item("5", "Dimmed Book"),
        item("6", "Another Book", 0.1f, DownloadState.DOWNLOADING),
        item("7", "Seventh Book"),
        item("8", "Eighth Book", 0.75f),
    )

    private val groups = listOf(
        BookGroup("a", "A. Writer", items.take(3)),
        BookGroup("b", "A Very Long Author Name And Another Equally Long Name", items.drop(3).take(2)),
        BookGroup("c", "Z. Last", items.drop(5)),
    )

    @Test fun largeGridDark() = shot("library_large_grid_dark", ThemeMode.DARK, 1.0f) { books(LibraryLayout.LARGE_GRID) }
    @Test fun largeGridLight() = shot("library_large_grid_light", ThemeMode.LIGHT, 1.0f) { books(LibraryLayout.LARGE_GRID) }
    @Test fun smallGridDark() = shot("library_small_grid_dark", ThemeMode.DARK, 1.0f) { books(LibraryLayout.SMALL_GRID) }
    @Test fun listDark() = shot("library_list_dark", ThemeMode.DARK, 1.0f) { books(LibraryLayout.LIST) }
    @Test fun listLight() = shot("library_list_light", ThemeMode.LIGHT, 1.0f) { books(LibraryLayout.LIST) }
    @Test fun listScale200() = shot("library_list_200", ThemeMode.DARK, 2.0f) { books(LibraryLayout.LIST) }
    @Test fun largeGridScale200() = shot("library_large_grid_200", ThemeMode.DARK, 2.0f) { books(LibraryLayout.LARGE_GRID) }
    @Test fun selectionMode() = shot("library_selection", ThemeMode.DARK, 1.0f) { books(LibraryLayout.LARGE_GRID, selection = setOf("2", "4")) }
    @Test fun authorGroups() = shot("library_groups_dark", ThemeMode.DARK, 1.0f) { GroupList(groups, onGroupClick = {}, modifier = Modifier) }
    @Test fun signInNeeded() = shot("library_sign_in_needed", ThemeMode.DARK, 1.0f) { SignInNeededState(listOf("Home server", "Other server")) }
    @Test @Config(qualifiers = "w800dp-h360dp-mdpi") fun landscapeGrid() = shot("library_landscape", ThemeMode.DARK, 1.0f) { books(LibraryLayout.LARGE_GRID) }
    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun tabletGrid() = shot("library_tablet", ThemeMode.DARK, 1.0f) { books(LibraryLayout.LARGE_GRID) }
    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun tabletList() = shot("library_tablet_list", ThemeMode.DARK, 1.0f) { books(LibraryLayout.LIST) }

    @Composable
    private fun books(layout: LibraryLayout, selection: Set<String>? = null) = BookLayout(
        items = items, layout = layout, isDimmed = { it.book.id == "5" }, onBookClick = {},
        onBookLongClick = {}, isFavourite = { it.book.id == "3" }, selection = selection, onToggleSelected = {},
    )

    private fun shot(name: String, theme: ThemeMode, fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotFrame(theme, fontScale, padded = false) { content() } }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
