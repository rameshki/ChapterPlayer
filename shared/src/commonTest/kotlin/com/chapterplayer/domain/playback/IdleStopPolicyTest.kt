package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class IdleStopPolicyTest {
    @Test
    fun `a stop of playback arms the timer once`() {
        val policy = IdleStopPolicy()
        assertEquals(IdleTimerAction.ARM, policy.onState(notPlaying = true, timerActive = false))
        assertEquals(IdleTimerAction.NONE, policy.onState(notPlaying = true, timerActive = true))
    }

    @Test
    fun `playing cancels a running timer and does nothing when there is none`() {
        val policy = IdleStopPolicy()
        assertEquals(IdleTimerAction.CANCEL, policy.onState(notPlaying = false, timerActive = true))
        assertEquals(IdleTimerAction.NONE, policy.onState(notPlaying = false, timerActive = false))
    }

    @Test
    fun `after the timer fired it does not arm again until something plays`() {
        val policy = IdleStopPolicy()
        assertEquals(IdleTimerAction.ARM, policy.onState(notPlaying = true, timerActive = false))
        policy.onFired()
        // the stop pauses the player, which reports not playing again and again: no new timer
        repeat(5) { assertEquals(IdleTimerAction.NONE, policy.onState(notPlaying = true, timerActive = false)) }
        // playback starts: the rule is reset
        assertEquals(IdleTimerAction.NONE, policy.onState(notPlaying = false, timerActive = false))
        assertEquals(IdleTimerAction.ARM, policy.onState(notPlaying = true, timerActive = false))
    }
}
