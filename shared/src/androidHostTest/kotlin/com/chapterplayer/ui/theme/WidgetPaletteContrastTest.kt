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
        // Shapes that carry no text still have to be seen: the empty track and the cover placeholder.
        listOf(p.cardTop, p.cardBottom).forEach { card ->
            assertTrue(contrast(p.track, card) >= 1.5, "$name track against the card")
            assertTrue(contrast(p.placeholder, card) >= 1.5, "$name cover placeholder against the card")
        }
        assertTrue(contrast(p.textSecondary, p.placeholder) >= 3.0, "$name placeholder book symbol")
    }

    @Test fun `the dark widget look is readable`() = check("dark", WidgetDarkPalette)
    @Test fun `the light widget look is readable`() = check("light", WidgetLightPalette)

    /** The widgets wear the app's midnight theme: the palette's own colours, nothing unrelated. */
    @Test fun `the dark look is the app's navy and amber`() {
        val d = WidgetDarkPalette
        assertEquals(DarkPalette.surfaceSecondary, d.cardTop)
        assertEquals(DarkPalette.textPrimary, d.textPrimary)
        assertEquals(DarkPalette.textSecondary, d.textSecondary)
        assertEquals(DarkPalette.textPrimary, d.icon) // light side buttons
        assertEquals(DarkPalette.amber, d.playFill)
        assertEquals(DarkPalette.onAmber, d.playGlyph) // a dark glyph on the amber circle
        assertEquals(DarkPalette.amber, d.progress)
        // The deeper navy lies between surfaceElevated and the background, channel by channel, and is bluest.
        assertBetween(d.cardBottom, DarkPalette.surfaceElevated, DarkPalette.background)
        assertTrue(d.cardBottom.blue > d.cardBottom.red, "the bottom stays navy")
        // The track (and the cover placeholder) is a dim navy between the card and the secondary text.
        assertBetween(d.track, DarkPalette.surfaceSecondary, DarkPalette.textSecondary)
        assertBetween(d.placeholder, DarkPalette.surfaceSecondary, DarkPalette.textSecondary)
        assertTrue(d.cardTop.luminance() > d.cardBottom.luminance(), "lighter at the top")
    }

    @Test fun `the light look is the app's warm paper with the dark amber`() {
        val l = WidgetLightPalette
        assertEquals(LightPalette.surfaceElevated, l.cardTop)
        assertEquals(LightPalette.surfaceSecondary, l.cardBottom)
        assertEquals(LightPalette.textPrimary, l.textPrimary)
        assertEquals(LightPalette.textSecondary, l.textSecondary)
        assertEquals(LightPalette.amber, l.playFill)
        assertEquals(Color.White, l.playGlyph)
        assertEquals(LightPalette.amber, l.progress)
        // The track and the placeholder: a warm grey between the palette's fieldBorder and divider.
        assertBetween(l.track, LightPalette.fieldBorder, LightPalette.divider)
        assertBetween(l.placeholder, LightPalette.fieldBorder, LightPalette.divider)
    }

    private fun assertBetween(c: Color, a: Color, b: Color) {
        fun inRange(v: Float, x: Float, y: Float) = v >= minOf(x, y) - 0.002f && v <= maxOf(x, y) + 0.002f
        assertTrue(inRange(c.red, a.red, b.red) && inRange(c.green, a.green, b.green) && inRange(c.blue, a.blue, b.blue), "$c between $a and $b")
    }

    /** The pre-Android 12 picker images draw the dark look with the palette's colours (and the synthetic cover's). */
    @Test fun `the old picker images use the dark palette`() {
        fun hex(c: Color) = "#%08X".format(c.toArgb())
        val d = WidgetDarkPalette
        val allowed = setOf(d.cardTop, d.textPrimary, d.textSecondary, d.playFill, d.playGlyph, d.progress, d.track).map(::hex).toSet() +
            setOf("#FF2F4A6B", "#FFD9A441", "#FFF2E6C9") // the synthetic cover
        for (name in listOf("widget_preview_listen", "widget_preview_now_playing", "widget_preview_continue")) {
            val xml = File("src/androidMain/res/drawable/$name.xml").readText()
            val used = Regex("android:fillColor=\"(#[0-9A-Fa-f]{8})\"").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
            assertTrue(used.isNotEmpty() && allowed.containsAll(used), "$name uses ${used - allowed}")
        }
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

    /** The picker previews' colour resources (day and night) carry the palette. */
    @Test fun `the preview colours use the palette`() {
        fun hex(c: Color) = "#%08X".format(c.toArgb())
        fun read(path: String): Map<String, String> = Regex("<color name=\"(\\w+)\">(#[0-9A-Fa-f]{8})</color>")
            .findAll(File("src/androidMain/res", path).readText()).associate { it.groupValues[1] to it.groupValues[2].uppercase() }
        fun expected(p: WidgetPalette) = mapOf(
            "widget_text" to hex(p.textPrimary), "widget_text_secondary" to hex(p.textSecondary), "widget_icon" to hex(p.icon),
            "widget_play_fill" to hex(p.playFill), "widget_play_glyph" to hex(p.playGlyph), "widget_progress" to hex(p.progress),
            "widget_track" to hex(p.track),
        )
        assertEquals(expected(WidgetLightPalette), read("values/widget_colors.xml"))
        assertEquals(expected(WidgetDarkPalette), read("values-night/widget_colors.xml"))
    }
}
