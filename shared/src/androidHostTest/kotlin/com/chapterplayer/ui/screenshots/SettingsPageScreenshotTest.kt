package com.chapterplayer.ui.screenshots

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.ui.settings.SectionTitle
import com.chapterplayer.ui.settings.SettingsPage
import com.chapterplayer.ui.settings.SwitchCard
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of a settings page (review R25): the shared page frame with switch cards on a phone, a phone in landscape
 * and a 10 inch tablet; the cards must stay a readable column, not stretch across the screen. Synthetic text only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-mdpi")
class SettingsPageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun phone() = shot("settings_page_phone")
    @Test @Config(qualifiers = "w800dp-h360dp-mdpi") fun landscape() = shot("settings_page_landscape")
    @Test @Config(qualifiers = "w1280dp-h800dp-mdpi") fun tablet() = shot("settings_page_tablet")

    @Composable
    private fun Page() = SettingsPage(title = "Downloads and offline", onBack = {}) {
        SectionTitle("Network")
        SwitchCard("Download on Wi-Fi only", "Downloads wait for Wi-Fi. Turn off to use mobile data too.", checked = true, onChange = {})
        Spacer(Modifier.height(8.dp))
        SwitchCard("Downloaded only", "Only downloaded books can play, even when online.", checked = false, onChange = {})
        SectionTitle("Books on several servers")
        SwitchCard("Keep progress in sync between copies", "Listening to one copy moves the others to the same place, on this phone and on their servers.", checked = true, onChange = {})
    }

    private fun shot(name: String) {
        compose.setContent { ScreenshotFrame(ThemeMode.DARK, 1.0f, padded = false) { Page() } }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
