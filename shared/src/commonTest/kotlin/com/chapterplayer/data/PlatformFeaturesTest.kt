package com.chapterplayer.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformFeaturesTest {

    @Test
    fun `Android has everything`() {
        assertTrue(PlatformFeatures.Full.playback)
        assertTrue(PlatformFeatures.Full.downloads)
        assertTrue(PlatformFeatures.Full.car)
    }

    @Test
    fun `the iPhone build browses only`() {
        assertFalse(PlatformFeatures.IosBrowsingOnly.playback)
        assertFalse(PlatformFeatures.IosBrowsingOnly.downloads)
        assertFalse(PlatformFeatures.IosBrowsingOnly.car)
    }
}
