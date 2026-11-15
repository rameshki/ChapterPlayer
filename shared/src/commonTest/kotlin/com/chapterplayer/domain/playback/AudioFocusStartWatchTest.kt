package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFocusStartWatchTest {

    @Test
    fun `focus refused right after Play in the background is a refused start`() {
        val watch = AudioFocusStartWatch()
        assertFalse(watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false))

        assertTrue(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false))
    }

    @Test
    fun `focus lost after sound played is a real loss to another app`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false)
        watch.onIsPlayingChanged(true)

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false))
    }

    @Test
    fun `with the app in front a refusal is not reported (the person sees the player)`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = true)

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = true))
    }

    @Test
    fun `a pause by the person is never a refusal`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false)

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = false, appInFront = false))
    }

    @Test
    fun `a refusal is reported once until Play is asked for again`() {
        val watch = AudioFocusStartWatch()
        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false)
        assertTrue(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false))

        assertFalse(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false))

        watch.onPlayWhenReadyChanged(true, byFocusLoss = false, appInFront = false)
        assertTrue(watch.onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false))
    }

    @Test
    fun `nothing asked for means nothing refused`() {
        assertFalse(AudioFocusStartWatch().onPlayWhenReadyChanged(false, byFocusLoss = true, appInFront = false))
    }
}
