package com.chapterplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.chapterplayer.data.settings.ThemeMode

// The theme follows the user's choice (dark, light or system). The palettes live in Palette.kt;
// the colour names in Color.kt read the current palette.

/** Whether the app should draw in its dark colours. */
fun resolveDarkTheme(mode: ThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemIsDark
}

// The two schemes take the same values; Material needs the dark or light builder so that
// components it styles itself (ripples, text fields) pick the right defaults.
private fun colorSchemeFor(p: ChapterPlayerPalette, dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = p.amber, onPrimary = p.onAmber, primaryContainer = p.amberDim, onPrimaryContainer = p.amber,
            secondary = p.textSecondary, onSecondary = p.onAmber,
            secondaryContainer = p.surfaceSecondary, onSecondaryContainer = p.textPrimary,
            background = p.background, onBackground = p.textPrimary,
            surface = p.surfaceElevated, onSurface = p.textPrimary,
            surfaceVariant = p.surfaceSecondary, onSurfaceVariant = p.textSecondary,
            error = p.error, onError = Color.White,
            outline = p.divider, outlineVariant = p.textTertiary
        )
    } else {
        lightColorScheme(
            primary = p.amber, onPrimary = p.onAmber, primaryContainer = p.amberDim, onPrimaryContainer = p.amber,
            secondary = p.textSecondary, onSecondary = p.onAmber,
            secondaryContainer = p.surfaceSecondary, onSecondaryContainer = p.textPrimary,
            background = p.background, onBackground = p.textPrimary,
            surface = p.surfaceElevated, onSurface = p.textPrimary,
            surfaceVariant = p.surfaceSecondary, onSurfaceVariant = p.textSecondary,
            error = p.error, onError = Color.White,
            outline = p.divider, outlineVariant = p.textTertiary
        )
    }

@Composable
fun ChapterPlayerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorSchemeFor(palette, dark),
            typography = chapterPlayerTypography(),
            content = content
        )
    }
}
