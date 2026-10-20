package com.chapterplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerFormatTest {
    @Test
    fun dragLabelsFollowTheFraction() {
        val labels = dragLabels(0.5f, 3_600_000L)
        assertEquals("30:00", labels.elapsed)
        assertEquals("30:00", labels.remaining)
    }

    @Test
    fun dragLabelsAtTheEnds() {
        assertEquals(SliderLabels("0:00", "1:00:00"), dragLabels(0f, 3_600_000L))
        assertEquals(SliderLabels("1:00:00", "0:00"), dragLabels(1f, 3_600_000L))
    }

    @Test
    fun dragLabelsClampBadInput() {
        assertEquals(SliderLabels("0:00", "1:40"), dragLabels(-3f, 100_000L))
        assertEquals(SliderLabels("1:40", "0:00"), dragLabels(7f, 100_000L))
        assertEquals(SliderLabels("0:00", "1:40"), dragLabels(Float.NaN, 100_000L))
        assertEquals(SliderLabels("0:00", "0:00"), dragLabels(0.5f, 0L))
    }

    @Test
    fun slidersNeedALength() {
        assertFalse(slidersEnabled(0L))
        assertFalse(slidersEnabled(-5L))
        assertTrue(slidersEnabled(1L))
    }

    @Test
    fun seekSettlesWhenStateIsNearTheReleasedValue() {
        assertTrue(seekSettled(0.5f, 0.5001f, 3_600_000L))
        assertFalse(seekSettled(0.5f, 0.2f, 3_600_000L))
        assertTrue(seekSettled(0.5f, 0.2f, 0L))
    }
}
