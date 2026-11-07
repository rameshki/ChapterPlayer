package com.chapterplayer.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardOverlapTest {

    @Test
    fun `no keyboard means no padding`() {
        assertEquals(0, keyboardOverlapPx(imeBottomPx = 0, gapBelowPx = 0))
        assertEquals(0, keyboardOverlapPx(imeBottomPx = 0, gapBelowPx = 200))
    }

    @Test
    fun `a screen that reaches the window bottom is padded by the whole keyboard`() {
        assertEquals(900, keyboardOverlapPx(imeBottomPx = 900, gapBelowPx = 0))
    }

    @Test
    fun `what lies below the screen already - tab bar or navigation bar - is not padded twice`() {
        assertEquals(700, keyboardOverlapPx(imeBottomPx = 900, gapBelowPx = 200))
    }

    @Test
    fun `a keyboard lower than the space below the screen needs no padding`() {
        assertEquals(0, keyboardOverlapPx(imeBottomPx = 150, gapBelowPx = 200))
    }

    @Test
    fun `a negative gap - content drawn past the window - counts as zero`() {
        assertEquals(900, keyboardOverlapPx(imeBottomPx = 900, gapBelowPx = -40))
    }
}
