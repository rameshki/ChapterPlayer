package com.chapterplayer

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.navigation.AppNavigation
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import com.chapterplayer.ui.theme.DarkPalette
import com.chapterplayer.ui.theme.LightPalette
import com.chapterplayer.ui.theme.resolveDarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/**
 * Single-activity entry point for the app.
 * Navigation between screens is handled entirely by Compose Navigation.
 */
class MainActivity : ComponentActivity() {

    private val settings: AppSettings by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read the saved choice before the first frame so a dark-theme user never sees a light flash.
        val saved = runBlocking { settings.themeMode.first() }
        // Paint the window and set the bars for the first frame, before Compose draws anything.
        val systemIsDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val startDark = resolveDarkTheme(saved, systemIsDark)
        window.setBackgroundDrawable(
            ColorDrawable((if (startDark) DarkPalette else LightPalette).background.toArgb())
        )
        applyBarStyle(startDark)
        setContent {
            val mode by settings.themeMode.collectAsState(initial = saved)
            val dark = resolveDarkTheme(mode, isSystemInDarkTheme())
            DisposableEffect(dark) {
                applyBarStyle(dark)
                onDispose { }
            }
            ChapterPlayerTheme(mode) {
                AppNavigation()
            }
        }
    }

    private fun applyBarStyle(dark: Boolean) {
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
}
