package com.chapterplayer.ui.library

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeDownloadController
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeNotificationPermission
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse

/**
 * A UI test (no image) of selection mode's top bar over the real view model and fakes, drawn by Robolectric through
 * Compose's multiplatform `runComposeUiTest`: the title follows the selection, Select all, the actions menu, Close.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionTopBarUiTest {

    private fun item(id: String) = BrowseItem(testBook(id).copy(serverId = "abs1", source = ServerSource.ABS), null, DownloadState.NOT_DOWNLOADED)

    private fun selection() = BookSelectionViewModel(
        FakeBookRepository(), FakeDownloadController(), FakeNotificationPermission(), FakePlaybackController(),
        FakeConnectivity(), Dispatchers.Main, overview = FakeDownloadOverview(),
    )

    @Test
    fun selectAllUpdatesTheTitleAndCloseLeavesTheMode() = runComposeUiTest {
        val vm = selection()
        vm.onItemsChanged(listOf(item("a"), item("b"), item("c")))
        vm.start("b")
        setContent {
            ChapterPlayerTheme(themeMode = ThemeMode.DARK) {
                val state by vm.uiState.collectAsState()
                SelectionTopBar(state, vm)
            }
        }

        onNodeWithText("1 selected").assertIsDisplayed()
        onNodeWithContentDescription("Select all").performClick()
        onNodeWithText("3 selected").assertIsDisplayed()

        onNodeWithContentDescription("Actions for the selected books").performClick()
        onNodeWithText("Mark as finished").assertIsDisplayed()
        onNodeWithText("Add to favourites").assertIsDisplayed()
        // Outside the app's provider the platform features fail closed: no Up next or download rows.
        onNodeWithText("Add to Up next").assertDoesNotExist()

        onNodeWithContentDescription("Close selection").performClick()
        waitForIdle()
        assertFalse(vm.uiState.value.active)
    }
}
