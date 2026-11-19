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
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
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
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}

/** A phone-width window at a medium density: small goldens that still show text clearly. */
internal const val SCREENSHOT_QUALIFIERS = "w360dp-h900dp-mdpi"

/**
 * How strictly a capture is compared with its golden. Goldens recorded on Windows and drawn on Linux (CI) differ in a small
 * share of the pixels (0.01 to 0.1 percent, glyph edges), by up to 4 of 255 in a colour channel. Dropbox Differ's distance is the
 * Euclidean one over the channels divided by 255, so 4 in each channel is 0.027: a distance of 0.02 (the first value) made the
 * player's images fail on CI. 0.05 (about 7 per channel) ignores that and still fails on a change a person would see: a 2 dp
 * shift moves whole glyphs by far more.
 */
internal val SCREENSHOT_OPTIONS = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = 0.05f)),
)

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
