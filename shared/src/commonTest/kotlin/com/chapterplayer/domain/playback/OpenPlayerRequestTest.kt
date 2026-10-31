package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenPlayerRequestTest {

    @Test
    fun `the extra key is fixed`() {
        assertEquals("open_player", EXTRA_OPEN_PLAYER)
    }

    @Test
    fun `nothing pending takes nothing`() {
        val r = OpenPlayerRequest()
        assertFalse(r.pending.value)
        assertFalse(r.take(hasBook = true))
    }

    @Test
    fun `a request is taken once when a book is loaded`() {
        val r = OpenPlayerRequest()
        r.request()
        assertTrue(r.pending.value)
        assertTrue(r.take(hasBook = true))
        assertFalse(r.pending.value)
        assertFalse(r.take(hasBook = true))
    }

    @Test
    fun `a request waits while nothing is loaded and is dropped on demand`() {
        val r = OpenPlayerRequest()
        r.request()
        assertFalse(r.take(hasBook = false))
        assertTrue(r.pending.value)
        r.drop()
        assertFalse(r.pending.value)
        assertFalse(r.take(hasBook = true))
    }

    @Test
    fun `a second request after taking works again`() {
        val r = OpenPlayerRequest()
        r.request()
        r.take(true)
        r.request()
        assertTrue(r.take(true))
    }
}
