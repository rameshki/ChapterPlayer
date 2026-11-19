package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLayoutTest {
    @Test
    fun `a wide phone or tablet keeps one row of five`() {
        assertEquals(5, toolsPerRow(380f, 1.0f))
        assertEquals(5, toolsPerRow(1000f, 1.3f))
    }

    @Test
    fun `a narrow phone uses two rows`() {
        assertEquals(3, toolsPerRow(328f, 1.0f))
    }

    @Test
    fun `large text uses two rows and then more rows`() {
        assertEquals(3, toolsPerRow(380f, 1.3f))
        assertEquals(2, toolsPerRow(328f, 2.0f))
    }

    @Test
    fun `a font scale under one counts as one`() {
        assertEquals(toolsPerRow(340f, 1.0f), toolsPerRow(340f, 0.85f))
    }
}
