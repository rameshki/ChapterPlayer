package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.ReadingProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class StartPositionTest {

    private fun progress(pos: Long, finished: Boolean = false) = ReadingProgress("a", pos, 100_000L, 1L, finished)

    @Test
    fun `no local progress lets the server say`() {
        assertEquals(USE_SERVER_POSITION, startPositionFor(null, 100_000L))
        assertEquals(-1L, USE_SERVER_POSITION)
    }

    @Test
    fun `a finished book or one at its end starts over`() {
        assertEquals(0L, startPositionFor(progress(50L, finished = true), 100_000L))
        assertEquals(0L, startPositionFor(progress(100_000L), 100_000L))
        assertEquals(0L, startPositionFor(progress(120_000L), 100_000L))
    }

    @Test
    fun `otherwise the saved position is used`() {
        assertEquals(40_000L, startPositionFor(progress(40_000L), 100_000L))
        assertEquals(0L, startPositionFor(progress(0L), 100_000L))
        assertEquals(0L, startPositionFor(progress(-5L), 100_000L))
        assertEquals(99_999L, startPositionFor(progress(99_999L), 100_000L))
    }
}
