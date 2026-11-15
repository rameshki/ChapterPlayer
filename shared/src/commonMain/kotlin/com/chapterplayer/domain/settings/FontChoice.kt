package com.chapterplayer.domain.settings

import com.chapterplayer.data.settings.AppFont

/** The font choices on Settings, Appearance, in the order they are listed. Pure text and size rules. */
val FONT_CHOICES: List<AppFont> = listOf(
    AppFont.CLASSIC, AppFont.BOOKISH, AppFont.INTER, AppFont.MANROPE, AppFont.LEXEND, AppFont.SYSTEM
)

fun fontName(font: AppFont): String = when (font) {
    AppFont.CLASSIC -> "Classic"
    AppFont.BOOKISH -> "Bookish"
    AppFont.INTER -> "Inter"
    AppFont.MANROPE -> "Manrope"
    AppFont.LEXEND -> "Lexend"
    AppFont.SYSTEM -> "System"
}

fun fontDescription(font: AppFont): String = when (font) {
    AppFont.CLASSIC -> "Playfair Display titles, DM Sans text (the original look)"
    AppFont.BOOKISH -> "Literata titles, Source Sans 3 text"
    AppFont.INTER -> "A clear, neutral sans serif"
    AppFont.MANROPE -> "A modern geometric sans serif"
    AppFont.LEXEND -> "Wide letters made for easier reading"
    AppFont.SYSTEM -> "Your phone's own font"
}

/**
 * Every text size (and line height) is multiplied by this. Lexend's letters are wider and taller than the
 * classic DM Sans at the same size, so it is drawn a little smaller to fit where the other fonts fit.
 */
fun fontSizeScale(font: AppFont): Float = if (font == AppFont.LEXEND) LEXEND_SIZE_SCALE else 1f

const val LEXEND_SIZE_SCALE = 0.94f
