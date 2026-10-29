package com.chapterplayer.domain.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListeningTicksTest {

    private fun tick(mono: Long, pos: Long, book: String? = "a", audible: Boolean = true, speed: Float = 1f) =
        ListenTick(book, audible, pos, speed, mono)

    @Test
    fun `normal playback counts the wall time between ticks`() {
        assertEquals(Listened(500, 500), listenedBetween(tick(1_000, 10_000), tick(1_500, 10_500)))
    }

    @Test
    fun `book time is wall time times the speed`() {
        assertEquals(Listened(1_000, 1_500), listenedBetween(tick(0, 0, speed = 1.5f), tick(1_000, 1_500, speed = 1.5f)))
        assertEquals(Listened(1_000, 2_000), listenedBetween(tick(0, 0, speed = 2f), tick(1_000, 2_000, speed = 2f)))
    }

    @Test
    fun `the first tick counts nothing`() {
        assertNull(listenedBetween(null, tick(0, 0)))
    }

    @Test
    fun `an interval that began paused or buffering counts nothing`() {
        assertNull(listenedBetween(tick(0, 0, audible = false), tick(500, 0)))
    }

    @Test
    fun `the interval that ends in a pause still counts`() {
        assertEquals(Listened(400, 400), listenedBetween(tick(0, 0), tick(400, 400, audible = false)))
    }

    @Test
    fun `a gap of more than five seconds counts nothing`() {
        assertEquals(Listened(5_000, 5_000), listenedBetween(tick(0, 0), tick(5_000, 5_000)))
        assertNull(listenedBetween(tick(0, 0), tick(5_001, 5_001)))
    }

    @Test
    fun `a seek counts nothing`() {
        assertNull(listenedBetween(tick(0, 10_000), tick(500, 40_500)))
        assertNull(listenedBetween(tick(0, 40_000), tick(500, 10_000)))
    }

    @Test
    fun `small position jitter is still playback`() {
        assertEquals(Listened(500, 500), listenedBetween(tick(0, 10_000), tick(500, 11_200)))
    }

    @Test
    fun `another book or no book counts nothing`() {
        assertNull(listenedBetween(tick(0, 0, book = "a"), tick(500, 500, book = "b")))
        assertNull(listenedBetween(tick(0, 0, book = null), tick(500, 500, book = null)))
    }

    @Test
    fun `a clock that did not move or went back counts nothing`() {
        assertNull(listenedBetween(tick(1_000, 0), tick(1_000, 0)))
        assertNull(listenedBetween(tick(1_000, 0), tick(900, 0)))
    }

    @Test
    fun `a zero speed is read as normal speed`() {
        assertEquals(Listened(1_000, 1_000), listenedBetween(tick(0, 0, speed = 0f), tick(1_000, 1_000)))
    }
}
