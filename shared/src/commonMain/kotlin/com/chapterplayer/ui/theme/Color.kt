package com.chapterplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// These names were fixed dark colours. They now follow the current theme's palette, so every
// screen that uses them works in both themes. Read them inside composables; code that runs
// later (a draw lambda) must read the colour first and use the local value.

val BackgroundDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceElevated
val SurfaceSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceSecondary

// The nav bar colour is set explicitly (Material 3 no longer tints the bar through elevation).
val NavBarSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.navBar
val AmberGold: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amber
val AmberDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amberDim
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary
val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textTertiary
val Divider: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.divider
val ErrorRed: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.error

// ─── Source badge and status colours: the same in both themes ──────────────
val AbsGreen = Color(0xFF2ECC71)
val AbsGreenDim = Color(0xFF1A5E38)
val PlexPurple = Color(0xFFE5A00D)   // Actually Plex uses orange/gold, but purple for distinction
val PlexPurpleActual = Color(0xFF9B59B6)
val PlexPurpleDim = Color(0xFF4A2C6E)
val SuccessGreen = Color(0xFF4CAF50)
