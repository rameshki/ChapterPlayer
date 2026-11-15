package com.chapterplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.chapterplayer.data.settings.AppFont
import com.chapterplayer.domain.settings.fontSizeScale
import com.chapterplayer.resources.Res
import com.chapterplayer.resources.dm_sans_bold
import com.chapterplayer.resources.dm_sans_medium
import com.chapterplayer.resources.dm_sans_regular
import com.chapterplayer.resources.inter_bold
import com.chapterplayer.resources.inter_medium
import com.chapterplayer.resources.inter_regular
import com.chapterplayer.resources.lexend_bold
import com.chapterplayer.resources.lexend_medium
import com.chapterplayer.resources.lexend_regular
import com.chapterplayer.resources.literata_bold
import com.chapterplayer.resources.literata_medium
import com.chapterplayer.resources.literata_regular
import com.chapterplayer.resources.manrope_bold
import com.chapterplayer.resources.manrope_medium
import com.chapterplayer.resources.manrope_regular
import com.chapterplayer.resources.playfair_display_bold
import com.chapterplayer.resources.playfair_display_italic
import com.chapterplayer.resources.playfair_display_regular
import com.chapterplayer.resources.source_sans_3_bold
import com.chapterplayer.resources.source_sans_3_medium
import com.chapterplayer.resources.source_sans_3_regular
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/**
 * Typography for ChapterPlayer. The user picks the font in Settings, Appearance ([AppFont]); every screen reads its
 * text styles from here, so the choice reaches the whole app (not the widgets or notifications, which Android draws
 * in the phone's own font).
 *
 * Each choice is two families: [AppFontFamilies.display] for the display and headline styles (titles), and
 * [AppFontFamilies.body] for everything else. Classic (the default, the original look) is Playfair Display + DM Sans,
 * Bookish is Literata + Source Sans 3, Inter, Manrope and Lexend use one family for both, and System uses the
 * platform's default font. The sizes are the same for every choice, times [fontSizeScale] (Lexend is drawn smaller).
 *
 * The font files (static weights 400, 500 and 700, from Google Fonts, all under the SIL Open Font License) live in
 * shared/src/commonMain/composeResources/font/ and are read through Compose Resources, so they work on every platform.
 */
class AppFontFamilies(val display: FontFamily, val body: FontFamily)

@Composable
private fun family(regular: FontResource, medium: FontResource, bold: FontResource): FontFamily =
    FontFamily(
        Font(regular, FontWeight.Normal),
        Font(medium, FontWeight.Medium),
        Font(bold, FontWeight.Bold)
    )

/** The families [font] draws with. A composable because the fonts are loaded through Compose Resources. */
@Composable
fun appFontFamilies(font: AppFont): AppFontFamilies = when (font) {
    AppFont.CLASSIC -> AppFontFamilies(
        display = FontFamily(
            Font(Res.font.playfair_display_regular, FontWeight.Normal),
            Font(Res.font.playfair_display_bold, FontWeight.Bold),
            Font(Res.font.playfair_display_italic, FontWeight.Normal, FontStyle.Italic)
        ),
        body = family(Res.font.dm_sans_regular, Res.font.dm_sans_medium, Res.font.dm_sans_bold)
    )
    AppFont.BOOKISH -> AppFontFamilies(
        display = family(Res.font.literata_regular, Res.font.literata_medium, Res.font.literata_bold),
        body = family(Res.font.source_sans_3_regular, Res.font.source_sans_3_medium, Res.font.source_sans_3_bold)
    )
    AppFont.INTER -> family(Res.font.inter_regular, Res.font.inter_medium, Res.font.inter_bold).let { AppFontFamilies(it, it) }
    AppFont.MANROPE -> family(Res.font.manrope_regular, Res.font.manrope_medium, Res.font.manrope_bold).let { AppFontFamilies(it, it) }
    AppFont.LEXEND -> family(Res.font.lexend_regular, Res.font.lexend_medium, Res.font.lexend_bold).let { AppFontFamilies(it, it) }
    AppFont.SYSTEM -> AppFontFamilies(FontFamily.Default, FontFamily.Default)
}

/** The app typography for [font]. */
@Composable
fun chapterPlayerTypography(font: AppFont = AppFont.CLASSIC): Typography {
    val families = appFontFamilies(font)
    val scale = fontSizeScale(font)
    return remember(families.display, families.body, scale) { buildTypography(families.display, families.body, scale) }
}

private fun buildTypography(display: FontFamily, body: FontFamily, scale: Float): Typography {
    fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int, spacing: TextUnit = TextUnit.Unspecified) =
        TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = (size * scale).sp,
            lineHeight = (line * scale).sp,
            letterSpacing = spacing
        )
    return Typography(
        // The display family for display and headline text
        displayLarge = style(display, FontWeight.Bold, 57, 64, (-0.25).sp),
        displayMedium = style(display, FontWeight.Bold, 45, 52),
        displaySmall = style(display, FontWeight.Normal, 36, 44),
        headlineLarge = style(display, FontWeight.Bold, 32, 40),
        headlineMedium = style(display, FontWeight.Bold, 28, 36),
        headlineSmall = style(display, FontWeight.Normal, 24, 32),
        // The body family for titles, body and UI text
        titleLarge = style(body, FontWeight.Medium, 22, 28),
        titleMedium = style(body, FontWeight.Medium, 16, 24, 0.15.sp),
        titleSmall = style(body, FontWeight.Medium, 14, 20, 0.1.sp),
        bodyLarge = style(body, FontWeight.Normal, 16, 24, 0.5.sp),
        bodyMedium = style(body, FontWeight.Normal, 14, 20, 0.25.sp),
        bodySmall = style(body, FontWeight.Normal, 12, 16, 0.4.sp),
        labelLarge = style(body, FontWeight.Medium, 14, 20, 0.1.sp),
        labelMedium = style(body, FontWeight.Medium, 12, 16, 0.5.sp),
        labelSmall = style(body, FontWeight.Medium, 11, 16, 0.5.sp)
    )
}
