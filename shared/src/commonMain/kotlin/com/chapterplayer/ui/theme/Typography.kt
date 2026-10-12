package com.chapterplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chapterplayer.resources.Res
import com.chapterplayer.resources.dm_sans_bold
import com.chapterplayer.resources.dm_sans_medium
import com.chapterplayer.resources.dm_sans_regular
import com.chapterplayer.resources.playfair_display_bold
import com.chapterplayer.resources.playfair_display_italic
import com.chapterplayer.resources.playfair_display_regular
import org.jetbrains.compose.resources.Font

/**
 * Typography for ChapterPlayer.
 *
 * Design spec:
 * - Playfair Display (serif) — book titles, display text
 * - DM Sans (sans-serif) — body text, metadata, UI labels
 *
 * The font files live in shared/src/commonMain/composeResources/font/ and are read through
 * Compose Resources, so the same fonts work on every platform:
 *   playfair_display_regular.ttf, playfair_display_bold.ttf, playfair_display_italic.ttf
 *   dm_sans_regular.ttf, dm_sans_medium.ttf, dm_sans_bold.ttf
 *
 * They come from Google Fonts (https://fonts.google.com).
 */
/** The app typography. Needs a composable because the fonts are loaded through Compose Resources. */
@Composable
fun chapterPlayerTypography(): Typography {
    val playfairDisplay = FontFamily(
        Font(Res.font.playfair_display_regular, FontWeight.Normal),
        Font(Res.font.playfair_display_bold, FontWeight.Bold),
        Font(Res.font.playfair_display_italic, FontWeight.Normal, FontStyle.Italic)
    )
    val dmSans = FontFamily(
        Font(Res.font.dm_sans_regular, FontWeight.Normal),
        Font(Res.font.dm_sans_medium, FontWeight.Medium),
        Font(Res.font.dm_sans_bold, FontWeight.Bold)
    )

    return remember(playfairDisplay, dmSans) {
        Typography(
            // Playfair Display for display/title text
            displayLarge = TextStyle(
                fontFamily = playfairDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp
            ),
            displayMedium = TextStyle(
                fontFamily = playfairDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 45.sp,
                lineHeight = 52.sp
            ),
            displaySmall = TextStyle(
                fontFamily = playfairDisplay,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 44.sp
            ),
            headlineLarge = TextStyle(
                fontFamily = playfairDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 40.sp
            ),
            headlineMedium = TextStyle(
                fontFamily = playfairDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 36.sp
            ),
            headlineSmall = TextStyle(
                fontFamily = playfairDisplay,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 32.sp
            ),
            // DM Sans for body and UI text
            titleLarge = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp
            ),
            titleMedium = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp
            ),
            titleSmall = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp
            ),
            bodyLarge = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp
            ),
            bodyMedium = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp
            ),
            bodySmall = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp
            ),
            labelLarge = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp
            ),
            labelMedium = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            ),
            labelSmall = TextStyle(
                fontFamily = dmSans,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
}
