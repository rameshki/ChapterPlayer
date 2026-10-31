package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRulesTest {

    @Test
    fun `rewind on resume grows with the time spent paused`() {
        assertEquals(0, rewindOnResumeMs(0))
        assertEquals(0, rewindOnResumeMs(9_999))
        assertEquals(5_000, rewindOnResumeMs(10_000))
        assertEquals(5_000, rewindOnResumeMs(299_999))
        assertEquals(10_000, rewindOnResumeMs(300_000))
        assertEquals(10_000, rewindOnResumeMs(3_599_999))
        assertEquals(20_000, rewindOnResumeMs(3_600_000))
        assertEquals(20_000, rewindOnResumeMs(Long.MAX_VALUE))
    }

    @Test
    fun `a negative pause is treated as no pause`() {
        assertEquals(0, rewindOnResumeMs(-1))
    }

    @Test
    fun `the resume position is the position minus the rewind and never below zero`() {
        assertEquals(80_000, resumeStart(100_000, 3_600_000))
        assertEquals(0, resumeStart(3_000, 3_600_000))
        assertEquals(100_000, resumeStart(100_000, 5_000))
        assertEquals(95_000, resumeStart(100_000, 10_000))
    }

    @Test
    fun `skip back goes 15 seconds and stops at the start`() {
        assertEquals(85_000, skipBackTo(100_000))
        assertEquals(0, skipBackTo(10_000))
        assertEquals(0, skipBackTo(0))
        assertEquals(5_000, skipBackTo(10_000, 5_000))
    }

    @Test
    fun `skip forward goes 30 seconds and stops at the end`() {
        assertEquals(130_000, skipForwardTo(100_000, 1_000_000))
        assertEquals(1_000_000, skipForwardTo(990_000, 1_000_000))
        assertEquals(1_000_000, skipForwardTo(1_000_000, 1_000_000))
        assertEquals(0, skipForwardTo(0, 0))
        assertEquals(15_000, skipForwardTo(10_000, 1_000_000, 5_000))
    }

    @Test
    fun `the speed steps are fixed`() {
        assertEquals(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f), PLAYBACK_SPEEDS)
    }

    @Test
    fun `next speed goes to the next higher step and wraps after the last`() {
        assertEquals(1.25f, nextSpeed(1.0f))
        assertEquals(3.0f, nextSpeed(2.5f))
        assertEquals(0.5f, nextSpeed(3.0f))
    }

    @Test
    fun `a speed that is not a step goes to the next higher step - beyond the last wraps`() {
        assertEquals(1.25f, nextSpeed(1.1f))
        assertEquals(0.5f, nextSpeed(0.3f))
        assertEquals(0.5f, nextSpeed(3.5f))
    }
}
