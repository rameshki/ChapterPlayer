package com.chapterplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.di.initKoin
import com.chapterplayer.navigation.AppNavigation
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import org.koin.compose.koinInject
import platform.UIKit.UIUserInterfaceStyle

/**
 * The root view controller of the iOS app. Koin is started once (the Swift app entry starts it too; the second call does
 * nothing), then the app shows the same navigation as Android ([AppNavigation]: the tabs, book details, the settings
 * hub, statistics) in the saved theme (new installs follow the system's dark or light). What iOS cannot do yet is
 * disabled or hidden by `PlatformFeatures.IosBrowsingOnly`. Compose draws edge to edge and pads for the safe area
 * (status bar, home indicator) and the keyboard itself.
 */
fun MainViewController() = run {
    initKoin()
    ComposeUIViewController {
        val settings = koinInject<AppSettings>()
        val mode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
        MatchSystemChrome(mode)
        ChapterPlayerTheme(mode) {
            AppNavigation()
        }
    }
}

/**
 * Gives the status bar, the keyboard and system sheets the app's theme: a Dark or Light choice overrides the
 * system's style on this view controller; Follow system leaves it unspecified, so the system's own setting (and
 * Compose's dark check, which reads the same trait) applies.
 */
@Composable
private fun MatchSystemChrome(mode: ThemeMode) {
    val controller = LocalUIViewController.current
    SideEffect { controller.overrideUserInterfaceStyle = interfaceStyleFor(mode) }
}

private fun interfaceStyleFor(mode: ThemeMode): UIUserInterfaceStyle = when (mode) {
    ThemeMode.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
    ThemeMode.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
    ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
}
