package com.chapterplayer.ui.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.ui.settings.BackupSettingsContent
import com.chapterplayer.ui.settings.BackupUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot test of Settings, Back up and restore (review R27): dark, light, font scale 2.0 and with a result line. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h900dp-mdpi")
class BackupSettingsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val result = BackupUiState(message = "Restored 2 favourites, 1 in Up next. 1 skipped: that book is not in this app yet (add the server, let it sync, then restore again).")

    @Test fun dark() = shot("settings_backup_dark", ThemeMode.DARK, 1.0f, BackupUiState())
    @Test fun light() = shot("settings_backup_light", ThemeMode.LIGHT, 1.0f, BackupUiState())
    @Test fun withResult() = shot("settings_backup_result", ThemeMode.DARK, 1.0f, result)
    @Test @Config(qualifiers = "w360dp-h2400dp-mdpi") fun scale200() = shot("settings_backup_200", ThemeMode.DARK, 2.0f, result)

    private fun shot(name: String, theme: ThemeMode, fontScale: Float, state: BackupUiState) {
        compose.setContent {
            ScreenshotFrame(theme, fontScale, padded = false) {
                BackupSettingsContent(state, copiedNote = false, onExport = {}, onRestore = {}, onBack = {})
            }
        }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
