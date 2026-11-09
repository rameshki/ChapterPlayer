package com.chapterplayer.domain.widgets

import com.chapterplayer.domain.playback.NEEDS_CONNECTION_TO_PLAY_TEXT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetStartReactionTest {

    @Test
    fun signInAgainOpensTheApp() {
        val r = widgetStartReaction("Sign in again in Settings")
        assertEquals("Sign in again in Settings", r.text)
        assertTrue(r.openApp)
    }

    @Test
    fun otherFailuresOnlyShowAShortReason() {
        assertEquals(WidgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT, openApp = false), widgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT))
        assertEquals(WidgetStartReaction(NEEDS_CONNECTION_TO_PLAY_TEXT, openApp = false), widgetStartReaction("This download needs a connection once before it plays offline"))
        assertEquals(WidgetStartReaction(SERVER_NOT_REACHABLE_TEXT, openApp = false), widgetStartReaction("Cannot reach the server"))
        assertEquals(WidgetStartReaction(CANT_PLAY_HERE_TEXT, openApp = false), widgetStartReaction(null))
        assertEquals(WidgetStartReaction(CANT_PLAY_HERE_TEXT, openApp = false), widgetStartReaction("Could not start playback"))
        // A start of this platform's own wording passes through (it is already safe and plain).
        assertEquals("This book's audio format cannot be played on this phone", widgetStartReaction("This book's audio format cannot be played on this phone").text)
    }

    @Test
    fun aRefusedForegroundStartOpensTheAppOnlyRightAfterAWidgetTap() {
        assertTrue(openAppAfterForegroundRefusal(nowMs = 10_000L, lastWidgetStartMs = 5_000L))
        assertFalse(openAppAfterForegroundRefusal(nowMs = 10_000L + HEADLESS_FALLBACK_WINDOW_MS, lastWidgetStartMs = 5_000L))
        assertFalse(openAppAfterForegroundRefusal(nowMs = 10_000L, lastWidgetStartMs = null)) // the car or a headset: never
        assertFalse(openAppAfterForegroundRefusal(nowMs = 1_000L, lastWidgetStartMs = 5_000L)) // a clock going back
    }
}
