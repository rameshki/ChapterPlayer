package com.chapterplayer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The home screen widgets' colours: the app's midnight theme (ui/theme/Palette.kt). Dark: a navy card that deepens towards
 * the bottom (the app's [DarkPalette] surfaces), the app's text colours, light side buttons and the round play button in the
 * app's amber with a dark glyph; the progress is amber on a dim navy track. Light: the app's warm paper surfaces
 * ([LightPalette]) with dark text and the light theme's darker amber for the play button, with a white glyph. Every colour
 * is one of the palette's or a mix of two of them (noted where so). The card's gradient is drawn by the `widget_card_*`
 * drawables, whose colours must equal [cardTop] and [cardBottom] (a test reads them).
 */
data class WidgetPalette(
    val cardTop: Color,
    val cardBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Back 15, forward 30, the chapter buttons and the placeholder book. */
    val icon: Color,
    val playFill: Color,
    val playGlyph: Color,
    val progress: Color,
    val track: Color,
    /** The cover placeholder's square. */
    val placeholder: Color,
)

val WidgetDarkPalette = WidgetPalette(
    // The app's navy card surface at the top ...
    cardTop = DarkPalette.surfaceSecondary,
    // ... down to a deeper navy: halfway between surfaceElevated (0xFF151A2B) and the warm black background (0xFF0E0D0B).
    cardBottom = Color(0xFF11131B),
    textPrimary = DarkPalette.textPrimary,
    textSecondary = DarkPalette.textSecondary,
    icon = DarkPalette.textPrimary,
    playFill = DarkPalette.amber,
    playGlyph = DarkPalette.onAmber,
    progress = DarkPalette.amber,
    // A dim navy: a quarter of the way from surfaceSecondary (0xFF1E2742) to textSecondary (0xFFAEABA4).
    track = Color(0xFF42485A),
    placeholder = DarkPalette.divider,
)

val WidgetLightPalette = WidgetPalette(
    cardTop = LightPalette.surfaceElevated,
    cardBottom = LightPalette.surfaceSecondary,
    textPrimary = LightPalette.textPrimary,
    textSecondary = LightPalette.textSecondary,
    icon = LightPalette.textPrimary,
    playFill = LightPalette.amber,
    playGlyph = LightPalette.onAmber,
    progress = LightPalette.amber,
    track = LightPalette.divider,
    placeholder = LightPalette.divider,
)
