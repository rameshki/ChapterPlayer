package com.chapterplayer.ui.stats

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.chapterplayer.ui.theme.ChapterPlayerPalette
import com.chapterplayer.ui.theme.DarkPalette
import com.chapterplayer.ui.theme.LightPalette
import kotlin.test.Test
import kotlin.test.assertTrue

class HeatColorsTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(name: String, palette: ChapterPlayerPalette) {
        val none = heatCellColor(palette, 0, future = false)
        val little = heatCellColor(palette, 1, future = false)
        // A day with a little listening must be clearly apart from a day with none, and from the card.
        assertTrue(contrast(little, none) >= 2.0, "$name level 1 against level 0: ${contrast(little, none)}")
        assertTrue(contrast(little, palette.surfaceElevated) >= 2.0, "$name level 1 against the card")
        // Each level is a step further from empty.
        val levels = (0..4).map { contrast(heatCellColor(palette, it, future = false), none) }
        assertTrue(levels.zipWithNext().all { (a, b) -> b > a }, "$name levels grow: $levels")
    }

    @Test fun `heat levels are distinct in the dark theme`() = check("dark", DarkPalette)
    @Test fun `heat levels are distinct in the light theme`() = check("light", LightPalette)
}
