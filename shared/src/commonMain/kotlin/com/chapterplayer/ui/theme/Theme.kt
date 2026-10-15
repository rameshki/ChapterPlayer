package com.chapterplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.chapterplayer.data.settings.ThemeMode

/**
 * ChapterPlayer uses a fixed dark theme — always dark, never light.
 * The design is built around the dark luxury aesthetic so we don't
 * implement a light theme variant.
 */
private val ChapterPlayerColorScheme = darkColorScheme(
    primary = AmberGold,
    onPrimary = BackgroundDeep,
    primaryContainer = AmberDim,
    onPrimaryContainer = AmberGold,

    secondary = TextSecondary,
    onSecondary = BackgroundDeep,
    secondaryContainer = SurfaceSecondary,
    onSecondaryContainer = TextPrimary,

    background = BackgroundDeep,
    onBackground = TextPrimary,

    surface = SurfaceElevated,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceSecondary,
    onSurfaceVariant = TextSecondary,

    error = ErrorRed,
    onError = Color.White,

    outline = Divider,
    outlineVariant = TextTertiary
)

/** Whether the app should draw in its dark colours. */
fun resolveDarkTheme(mode: ThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemIsDark
}

@Composable
fun ChapterPlayerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ChapterPlayerColorScheme,
        typography = chapterPlayerTypography(),
        content = content
    )
}
