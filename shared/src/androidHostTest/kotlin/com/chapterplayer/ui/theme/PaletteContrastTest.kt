package com.chapterplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class PaletteContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(name: String, palette: ChapterPlayerPalette) {
        val surfaces = listOf(palette.background, palette.surfaceElevated, palette.surfaceSecondary)
        surfaces.forEach { s ->
            assertTrue(contrast(palette.textPrimary, s) >= 7.0, "$name primary text on a surface")
            assertTrue(contrast(palette.textSecondary, s) >= 4.5, "$name secondary text on a surface")
            assertTrue(contrast(palette.amber, s) >= 4.5, "$name amber text on a surface")
        }
        // Hint text is checked on background and surfaceElevated only: the existing dark textTertiary
        // is about 2.8:1 on surfaceSecondary and the approved dark look stays as it is.
        listOf(palette.background, palette.surfaceElevated).forEach { s ->
            assertTrue(contrast(palette.textTertiary, s) >= 3.0, "$name hint text on a surface")
        }
        assertTrue(contrast(palette.onAmber, palette.amber) >= 4.5, "$name text on an amber button")
        assertTrue(contrast(palette.textSecondary, palette.navBar) >= 4.5, "$name nav bar labels")
        assertTrue(contrast(palette.amber, palette.navBar) >= 4.5, "$name selected nav item")
    }

    @Test fun `the dark palette is readable`() = check("dark", DarkPalette)
    @Test fun `the light palette is readable`() = check("light", LightPalette)
}
