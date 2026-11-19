package com.chapterplayer.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.ui.settings.AbsAddSection
import com.chapterplayer.ui.settings.AbsSetupState
import com.chapterplayer.ui.settings.AbsStep
import com.chapterplayer.ui.settings.JellyfinAddSection
import com.chapterplayer.ui.settings.JellyfinSetupState
import com.chapterplayer.ui.settings.JellyfinStep
import com.chapterplayer.ui.settings.LibraryChoice
import com.chapterplayer.ui.settings.QuickConnectUi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot test of the Add Server forms (review R2): Audiobookshelf sign-in and its error, Jellyfin sign-in, the
 * Quick Connect wait with a code, and the libraries step; each in dark and light, and at font scale 2.0 in a tall
 * window (the page scrolls in the app, the test shows it whole). Synthetic names and addresses only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h700dp-mdpi")
class AddServerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val absForm = AbsSetupState(address = "http://abs.example.test:13378", username = "listener")
    private val absError = absForm.copy(signInError = "Couldn't sign in. Check the username and password.")
    private val jfForm = JellyfinSetupState(address = "https://jellyfin.example.test", username = "listener")
    private val quick = jfForm.copy(quickConnect = QuickConnectUi(code = "482913"), isSigningIn = true)
    private val libraries = JellyfinSetupState(
        step = JellyfinStep.LIBRARIES,
        libraries = listOf(LibraryChoice("1", "Audiobooks", true), LibraryChoice("2", "Podcasts and a very long library name", false)),
    )

    @Test fun absFormDark() = shot("add_abs_form", ThemeMode.DARK, 1.0f) { abs(absForm) }
    @Test fun absFormLight() = shot("add_abs_form_light", ThemeMode.LIGHT, 1.0f) { abs(absForm) }
    @Test @Config(qualifiers = "w360dp-h1500dp-mdpi") fun absFormScale200() = shot("add_abs_form_200", ThemeMode.DARK, 2.0f) { abs(absForm) }
    @Test fun absErrorDark() = shot("add_abs_error", ThemeMode.DARK, 1.0f) { abs(absError) }
    @Test fun jellyfinFormDark() = shot("add_jf_form", ThemeMode.DARK, 1.0f) { jf(jfForm) }
    @Test fun jellyfinFormLight() = shot("add_jf_form_light", ThemeMode.LIGHT, 1.0f) { jf(jfForm) }
    @Test @Config(qualifiers = "w360dp-h1500dp-mdpi") fun jellyfinFormScale200() = shot("add_jf_form_200", ThemeMode.DARK, 2.0f) { jf(jfForm) }
    @Test fun quickConnectDark() = shot("add_jf_quick_connect", ThemeMode.DARK, 1.0f) { jf(quick) }
    @Test fun quickConnectLight() = shot("add_jf_quick_connect_light", ThemeMode.LIGHT, 1.0f) { jf(quick) }
    @Test fun librariesDark() = shot("add_jf_libraries", ThemeMode.DARK, 1.0f) { jf(libraries) }
    @Test @Config(qualifiers = "w360dp-h1500dp-mdpi") fun librariesScale200() = shot("add_jf_libraries_200", ThemeMode.DARK, 2.0f) { jf(libraries) }

    @Composable
    private fun abs(state: AbsSetupState) = AbsAddSection(state, {}, {}, {}, {}, {}, {}, {}, {})

    @Composable
    private fun jf(state: JellyfinSetupState) = JellyfinAddSection(state, {}, {}, {}, {}, {}, {}, {}, {})

    private fun shot(name: String, theme: ThemeMode, fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotFrame(theme, fontScale) { content() } }
        compose.onRoot().captureRoboImage(screenshotPath(name), roborazziOptions = SCREENSHOT_OPTIONS)
    }
}
