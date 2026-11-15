package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFocusStartWatchTest {

    private val T0 = 100_000L

    @Test
    fun `focus refused right after Play in the background is a refused start`() {
        val watch = AudioFocusStartWatch()
        assertFalse(watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0))

        assertTrue(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0))
    }

    @Test
    fun `focus lost after sound played is a real loss to another app`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0)
        watch.onIsPlayingChanged(true)

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0))
    }

    @Test
    fun `with the app in front a refusal is not reported because the person sees the player`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = true, nowMs = T0)

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = true, nowMs = T0))
    }

    @Test
    fun `a pause by the person is never a refusal`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0)

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = false, appInFront = false, nowMs = T0))
    }

    @Test
    fun `a refusal is reported once until Play is asked for again`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0)
        assertTrue(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0))

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0))

        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0)
        assertTrue(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0))
    }

    @Test
    fun `nothing asked for means nothing refused`() {
        assertFalse(AudioFocusStartWatch().onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0))
    }

    @Test
    fun `a loss within the window after Play is a refused start`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0)

        assertTrue(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0 + FOCUS_REFUSAL_WINDOW_MS))
    }

    @Test
    fun `slow buffering then a later focus loss is a genuine loss`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0)
        // Still buffering (no sound yet) when another app takes the audio 5 s later.

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0 + 5_000))
    }

    @Test
    fun `just past the window counts as a genuine loss`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false, nowMs = T0)

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false, nowMs = T0 + FOCUS_REFUSAL_WINDOW_MS + 1))
    }
}
