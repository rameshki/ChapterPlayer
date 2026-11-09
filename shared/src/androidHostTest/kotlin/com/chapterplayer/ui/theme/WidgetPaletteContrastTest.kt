package com.chapterplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetPaletteContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(name: String, p: WidgetPalette) {
        // Text and icons sit anywhere on the gradient: both ends count.
        listOf(p.cardTop, p.cardBottom).forEach { card ->
            assertTrue(contrast(p.textPrimary, card) >= 7.0, "$name title on the card")
            assertTrue(contrast(p.textSecondary, card) >= 4.5, "$name author and time left on the card")
            assertTrue(contrast(p.icon, card) >= 4.5, "$name buttons on the card")
            assertTrue(contrast(p.playFill, card) >= 3.0, "$name play circle against the card")
            assertTrue(contrast(p.progress, card) >= 3.0, "$name progress against the card")
        }
        assertTrue(contrast(p.playGlyph, p.playFill) >= 4.5, "$name play glyph on its circle")
        assertTrue(contrast(p.progress, p.track) >= 3.0, "$name progress on its track")
    }

    @Test fun `the dark widget look is readable`() = check("dark", WidgetDarkPalette)
    @Test fun `the light widget look is readable`() = check("light", WidgetLightPalette)

    @Test fun `the dark look is white on charcoal and the light one reverses it`() {
        assertEquals(Color.White, WidgetDarkPalette.textPrimary)
        assertEquals(Color.White, WidgetDarkPalette.playFill)
        assertEquals(Color.White, WidgetLightPalette.playGlyph)
        assertTrue(WidgetDarkPalette.cardTop.luminance() > WidgetDarkPalette.cardBottom.luminance(), "lighter at the top")
    }

    /** The gradient drawables carry the palette's card colours (resources cannot read Kotlin constants). */
    @Test fun `the card drawables use the palette`() {
        val res = File("src/androidMain/res")
        fun colours(path: String): Pair<String, String> {
            val xml = File(res, path).readText()
            val start = Regex("android:startColor=\"(#[0-9A-Fa-f]{8})\"").find(xml)!!.groupValues[1]
            val end = Regex("android:endColor=\"(#[0-9A-Fa-f]{8})\"").find(xml)!!.groupValues[1]
            return start.uppercase() to end.uppercase()
        }
        fun hex(c: Color) = "#%08X".format(c.toArgb())
        val dark = hex(WidgetDarkPalette.cardTop) to hex(WidgetDarkPalette.cardBottom)
        val light = hex(WidgetLightPalette.cardTop) to hex(WidgetLightPalette.cardBottom)
        assertEquals(dark, colours("drawable/widget_card_dark.xml"))
        assertEquals(light, colours("drawable/widget_card_light.xml"))
        assertEquals(light, colours("drawable/widget_card_system.xml"))
        assertEquals(dark, colours("drawable-night/widget_card_system.xml"))
    }
}
