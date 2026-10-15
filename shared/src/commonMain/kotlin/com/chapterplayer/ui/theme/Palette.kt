package com.chapterplayer.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Every colour the app draws from, for one theme. */
@Immutable
data class ChapterPlayerPalette(
    val background: Color,
    val surfaceElevated: Color,
    val surfaceSecondary: Color,
    val navBar: Color,
    val amber: Color,
    val amberDim: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    /** Outline of an empty text field. */
    val fieldBorder: Color,
    val error: Color,
    /** Text or icons drawn on an amber button. */
    val onAmber: Color
)

val DarkPalette = ChapterPlayerPalette(
    background = Color(0xFF0E0D0B),
    surfaceElevated = Color(0xFF1A1916),
    surfaceSecondary = Color(0xFF252320),
    navBar = Color(0xFF2B261A),
    amber = Color(0xFFE8B84B),
    amberDim = Color(0xFF8A6E2D),
    textPrimary = Color(0xFFF0EDE8),
    textSecondary = Color(0xFFABA89F),
    textTertiary = Color(0xFF6B6860),
    divider = Color(0xFF2A2926),
    fieldBorder = Color(0xFF252320),
    error = Color(0xFFCF6679),
    onAmber = Color(0xFF0E0D0B)
)

/** The amber is darker than in the dark theme so amber text stays readable on light surfaces. */
val LightPalette = ChapterPlayerPalette(
    background = Color(0xFFF7F5F0),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFEBE8E0),
    navBar = Color(0xFFEFE7D2),
    amber = Color(0xFF8A5A00),
    amberDim = Color(0xFFC9A55B),
    textPrimary = Color(0xFF1D1B17),
    textSecondary = Color(0xFF5F5A50),
    textTertiary = Color(0xFF7A7568),
    divider = Color(0xFFDDD8CC),
    fieldBorder = Color(0xFF8D877B),
    error = Color(0xFFB3261E),
    onAmber = Color(0xFFFFFFFF)
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
