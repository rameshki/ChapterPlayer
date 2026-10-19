package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartAndAccessRulesTest {

    @Test
    fun `a requested position of zero or more is used as it is`() {
        assertEquals(0L, resolveStartMs(0, 50_000, 100_000))
        assertEquals(30_000L, resolveStartMs(30_000, 50_000, 100_000))
    }

    @Test
    fun `a negative request takes the server position`() {
        assertEquals(50_000L, resolveStartMs(-1, 50_000, 100_000))
    }

    @Test
    fun `a negative request on a book the server counts as finished starts at the beginning`() {
        assertEquals(0L, resolveStartMs(-1, 95_000, 100_000))
        assertEquals(94_999L, resolveStartMs(-1, 94_999, 100_000))
    }

    @Test
    fun `a bad server position never goes below zero`() {
        assertEquals(0L, resolveStartMs(-1, -40, 100_000))
        assertEquals(7L, resolveStartMs(-1, 7, 0))
    }

    @Test
    fun `only our own app may queue our own track ids`() {
        assertTrue(isOwnTrackRequest("com.chapterplayer.debug", "com.chapterplayer.debug", "abs_li_1/0"))
        assertTrue(isOwnTrackRequest("p", "p", "abs_li_1/12"))
        assertFalse(isOwnTrackRequest("com.evil", "com.chapterplayer", "abs_li_1/0"))
        assertFalse(isOwnTrackRequest(null, "p", "abs_li_1/0"))
        assertFalse(isOwnTrackRequest("p", "p", "abs_li_1"))
        assertFalse(isOwnTrackRequest("p", "p", "abs_li_1/"))
        assertFalse(isOwnTrackRequest("p", "p", "/3"))
        assertFalse(isOwnTrackRequest("p", "p", "abs_li_1/x"))
        assertFalse(isOwnTrackRequest("p", "p", "http://evil.test/a.mp3"))
    }
}
