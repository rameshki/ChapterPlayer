package com.chapterplayer.ui.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.whatsnew.parseChangelog
import com.chapterplayer.ui.settings.WhatsNewContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot test of the "What's new" sheet body (review R28), with synthetic notes; dark, light and font scale 2.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h740dp-mdpi")
class WhatsNewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val notes = parseChangelog(
        """
        ## 9.9.9 (1 January 2030)
        **New**
        - **A new thing:** what it does and where to find it in Settings.
        - Another change with a longer description that wraps onto a second line so the layout can be judged.

        **Fixed**
        - A problem that no longer happens.
        """.trimIndent()
    ).single()

    @Test fun dark() = shot("whats_new_dark", ThemeMode.DARK, 1.0f)
    @Test fun light() = shot("whats_new_light", ThemeMode.LIGHT, 1.0f)
    @Test @Config(qualifiers = "w360dp-h1400dp-mdpi") fun scale200() = shot("whats_new_200", ThemeMode.DARK, 2.0f)

    private fun shot(name: String, theme: ThemeMode, fontScale: Float) {
        compose.setContent { ScreenshotFrame(theme, fontScale, padded = false) { WhatsNewContent(notes, onClose = {}) } }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
