package com.chapterplayer.ui.stats

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.chapterplayer.ui.theme.ChapterPlayerPalette

/**
 * How far from the empty square towards amber each heat level goes. Level 1 starts half way, so even a little
 * listening stands clearly apart from none in both themes (HeatColorsTest checks the contrast).
 */
internal fun heatFraction(level: Int): Float = when {
    level <= 0 -> 0f
    level == 1 -> 0.5f
    level == 2 -> 0.67f
    level == 3 -> 0.84f
    else -> 1f
}

/** The colour of a square of the "Every day" grid; days still to come are faint. */
internal fun heatCellColor(palette: ChapterPlayerPalette, level: Int, future: Boolean): Color {
    val empty = palette.surfaceSecondary
    if (future) return empty.copy(alpha = 0.35f)
    return lerp(empty, palette.amber, heatFraction(level))
}
