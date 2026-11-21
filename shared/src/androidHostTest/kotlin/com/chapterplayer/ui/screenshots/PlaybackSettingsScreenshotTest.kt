package com.chapterplayer.ui.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.ui.settings.PlaybackSettingsContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot test of Settings, Playback (review R26): the two skip-length lists, dark and light and at font scale 2.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1100dp-mdpi")
class PlaybackSettingsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun dark() = shot("settings_playback_dark", ThemeMode.DARK, 1.0f)
    @Test fun light() = shot("settings_playback_light", ThemeMode.LIGHT, 1.0f)
    @Test @Config(qualifiers = "w360dp-h2000dp-mdpi") fun scale200() = shot("settings_playback_200", ThemeMode.DARK, 2.0f)

    private fun shot(name: String, theme: ThemeMode, fontScale: Float) {
        compose.setContent {
            ScreenshotFrame(theme, fontScale, padded = false) {
                PlaybackSettingsContent(backSec = 15, forwardSec = 45, onSelectBack = {}, onSelectForward = {}, onNavigateUp = {})
            }
        }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
