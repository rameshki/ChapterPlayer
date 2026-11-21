package com.chapterplayer.domain.playback

import com.chapterplayer.domain.widgets.ListenWidgetState
import com.chapterplayer.domain.widgets.withSkips
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkipLengthsTest {
    @Test fun `a chosen length is kept and anything else falls back to the default`() {
        assertEquals(45, normalizedSkipSec(45, SKIP_BACK_CHOICES_SEC, DEFAULT_SKIP_BACK_SEC))
        assertEquals(15, normalizedSkipSec(null, SKIP_BACK_CHOICES_SEC, DEFAULT_SKIP_BACK_SEC))
        assertEquals(15, normalizedSkipSec(7, SKIP_BACK_CHOICES_SEC, DEFAULT_SKIP_BACK_SEC))
        assertEquals(30, normalizedSkipSec(-30, SKIP_FORWARD_CHOICES_SEC, DEFAULT_SKIP_FORWARD_SEC))
        assertEquals(90, normalizedSkipSec(90, SKIP_FORWARD_CHOICES_SEC, DEFAULT_SKIP_FORWARD_SEC))
    }

    @Test fun `the defaults are among the choices`() {
        assertTrue(DEFAULT_SKIP_BACK_SEC in SKIP_BACK_CHOICES_SEC)
        assertTrue(DEFAULT_SKIP_FORWARD_SEC in SKIP_FORWARD_CHOICES_SEC)
    }

    @Test fun `labels and summary name the length`() {
        assertEquals("Back 10 seconds", skipBackLabel(10))
        assertEquals("Forward 45 seconds", skipForwardLabel(45))
        assertEquals("Back 15 s, forward 30 s", skipSummary(15, 30))
    }

    @Test fun `only the controls state takes skip lengths`() {
        val controls = ListenWidgetState.Controls("b", "T", "A", null, 10, isPlaying = true, canSkip = true, canJumpChapters = false)
        assertEquals(15 to 30, controls.skipBackSec to controls.skipForwardSec)
        val changed = controls.withSkips(60, 90) as ListenWidgetState.Controls
        assertEquals(60 to 90, changed.skipBackSec to changed.skipForwardSec)
        val resume = ListenWidgetState.Resume("b", "T", "A", 10, "1 h left")
        assertEquals(resume, resume.withSkips(60, 90))
    }
}
