package com.chapterplayer.domain.settings

import com.chapterplayer.data.settings.AppFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontChoiceTest {

    @Test
    fun `every font is offered once and Classic comes first`() {
        assertEquals(AppFont.entries.toSet(), FONT_CHOICES.toSet())
        assertEquals(AppFont.entries.size, FONT_CHOICES.size)
        assertEquals(AppFont.CLASSIC, FONT_CHOICES.first())
    }

    @Test
    fun `every font has a distinct name and a description`() {
        val names = FONT_CHOICES.map(::fontName)
        assertEquals(names.size, names.toSet().size)
        assertEquals(listOf("Classic", "Bookish", "Inter", "Manrope", "Lexend", "System"), names)
        FONT_CHOICES.forEach { assertTrue(fontDescription(it).isNotBlank()) }
    }

    @Test
    fun `only Lexend is drawn smaller`() {
        assertEquals(LEXEND_SIZE_SCALE, fontSizeScale(AppFont.LEXEND))
        assertTrue(LEXEND_SIZE_SCALE in 0.9f..0.99f)
        FONT_CHOICES.filter { it != AppFont.LEXEND }.forEach { assertEquals(1f, fontSizeScale(it)) }
    }
}
