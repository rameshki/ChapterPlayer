package com.chapterplayer

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.di.initKoin
import com.chapterplayer.navigation.IosAppNavigation
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import org.koin.compose.koinInject

/**
 * The root view controller of the iOS app. Koin is started once (the Swift app entry starts it too; the second call does
 * nothing), then the app shows what works on iOS so far ([IosAppNavigation]) in the saved theme.
 */
fun MainViewController() = run {
    initKoin()
    ComposeUIViewController {
        val settings = koinInject<AppSettings>()
        val mode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
        ChapterPlayerTheme(mode) {
            IosAppNavigation()
        }
    }
}
