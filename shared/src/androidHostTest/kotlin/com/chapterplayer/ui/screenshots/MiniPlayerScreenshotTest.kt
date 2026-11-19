package com.chapterplayer.ui.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.ui.player.MiniPlayerContent
import com.chapterplayer.ui.player.MiniPlayerState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of the mini player: a short title playing, a very long title paused, and the long one at large text,
 * in dark and light; plus a phone in landscape and a 10 inch tablet width (review R25: nothing stretched or clipped).
 * Synthetic titles only. Record with recordRoborazziAndroidHostTest (docs/architecture/testing.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class MiniPlayerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val short = MiniPlayerState("A Short Title", "Chapter 3", null, 0.4f, showPause = true)
    private val long = MiniPlayerState(
        "An Extraordinarily Long Audiobook Title That Cannot Possibly Fit On One Line (Unabridged)",
        "Chapter 12. A Chapter Name That Is Also Far Too Long To Fit Beside The Controls", null, 0.85f, showPause = false,
    )

    @Test
    fun darkShortAndLong() = capture(ThemeMode.DARK, 1.0f, "mini_player_dark_100")

    @Test
    fun lightShortAndLong() = capture(ThemeMode.LIGHT, 1.0f, "mini_player_light_100")

    @Test
    fun darkAtScale200() = capture(ThemeMode.DARK, 2.0f, "mini_player_dark_200")

    @Test
    @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun landscapePhone() = capture(ThemeMode.DARK, 1.0f, "mini_player_landscape")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tablet() = capture(ThemeMode.DARK, 1.0f, "mini_player_tablet")

    private fun capture(theme: ThemeMode, fontScale: Float, name: String) {
        compose.setContent {
            ScreenshotFrame(theme, fontScale) {
                Column {
                    MiniPlayerContent(short, onOpenPlayer = {}, onPlayPause = {})
                    Spacer(Modifier.height(12.dp))
                    MiniPlayerContent(long, onOpenPlayer = {}, onPlayPause = {})
                }
            }
        }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
