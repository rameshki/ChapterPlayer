package com.chapterplayer.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.settings.AppFont
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.ui.settings.FontCard
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of Settings, Appearance's font picker in dark and light at font scale 1.0 and 1.3.
 * Goldens: src/androidHostTest/screenshots. Record with recordRoborazziAndroidHostTest, check with
 * verifyRoborazziAndroidHostTest (docs/architecture/testing.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class FontCardScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun darkAtScale100() = capture(ThemeMode.DARK, 1.0f, "font_card_dark_100")

    @Test
    fun darkAtScale130() = capture(ThemeMode.DARK, 1.3f, "font_card_dark_130")

    @Test
    fun lightAtScale100() = capture(ThemeMode.LIGHT, 1.0f, "font_card_light_100")

    @Test
    fun lightAtScale130() = capture(ThemeMode.LIGHT, 1.3f, "font_card_light_130")

    private fun capture(theme: ThemeMode, fontScale: Float, name: String) {
        compose.setContent {
            ScreenshotFrame(theme, fontScale) {
                FontCard(selected = AppFont.BOOKISH, onSelect = {})
            }
        }
        compose.onRoot().captureRoboImage(screenshotPath(name))
    }
}

/** A phone-width window at a medium density: small goldens that still show text clearly. */
internal const val SCREENSHOT_QUALIFIERS = "w360dp-h900dp-mdpi"

/** Where a golden lives (the plugin's outputDir); one file per name. */
internal fun screenshotPath(name: String) = "src/androidHostTest/screenshots/$name.png"

/** The app theme on the page colour, with the font scale applied the way the system applies it. */
@Composable
internal fun ScreenshotFrame(theme: ThemeMode, fontScale: Float, content: @Composable () -> Unit) {
    ChapterPlayerTheme(themeMode = theme, font = AppFont.CLASSIC) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                content()
            }
        }
    }
}
