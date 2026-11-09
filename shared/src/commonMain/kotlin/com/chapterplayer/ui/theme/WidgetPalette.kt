package com.chapterplayer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The home screen widgets' colours. Neutral on purpose (no navy, no amber): a charcoal card that darkens towards the
 * bottom with white text, white controls and a round white play button in the dark theme; the light theme reverses
 * it. The card's gradient is drawn by the `widget_card_*` drawables, whose colours must equal [cardTop] and
 * [cardBottom] (a test reads them).
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
    cardTop = Color(0xFF3F3F42),
    cardBottom = Color(0xFF19191B),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC2C2C7),
    icon = Color(0xFFFFFFFF),
    playFill = Color(0xFFFFFFFF),
    playGlyph = Color(0xFF19191B),
    progress = Color(0xFFFFFFFF),
    track = Color(0xFF6B6B70),
    placeholder = Color(0xFF55555A),
)

val WidgetLightPalette = WidgetPalette(
    cardTop = Color(0xFFFFFFFF),
    cardBottom = Color(0xFFE6E6EB),
    textPrimary = Color(0xFF111114),
    textSecondary = Color(0xFF55555C),
    icon = Color(0xFF1C1C1E),
    playFill = Color(0xFF1C1C1E),
    playGlyph = Color(0xFFFFFFFF),
    progress = Color(0xFF1C1C1E),
    track = Color(0xFFC6C6CC),
    placeholder = Color(0xFFD1D1D6),
)
