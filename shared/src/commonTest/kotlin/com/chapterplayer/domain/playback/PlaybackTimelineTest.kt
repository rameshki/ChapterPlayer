package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTimelineTest {

    private val three = listOf(
        TrackSpan(0, 0, 1_000),
        TrackSpan(1, 1_000, 2_000),
        TrackSpan(2, 3_000, 500),
    )

    @Test
    fun `no tracks gives no position`() {
        assertNull(locate(emptyList(), 100))
    }

    @Test
    fun `a position inside a track gives that track and the offset - a start is inclusive`() {
        assertEquals(TrackPosition(0, 0), locate(three, 0))
        assertEquals(TrackPosition(0, 999), locate(three, 999))
        assertEquals(TrackPosition(1, 0), locate(three, 1_000))
        assertEquals(TrackPosition(1, 1_999), locate(three, 2_999))
        assertEquals(TrackPosition(2, 0), locate(three, 3_000))
        assertEquals(TrackPosition(2, 499), locate(three, 3_499))
    }

    @Test
    fun `a position at or past the end gives the end of the last track`() {
        assertEquals(TrackPosition(2, 500), locate(three, 3_500))
        assertEquals(TrackPosition(2, 500), locate(three, 99_999))
    }

    @Test
    fun `a negative position or one before the first start gives the start of the first track`() {
        assertEquals(TrackPosition(0, 0), locate(three, -5))
        assertEquals(TrackPosition(0, 0), locate(listOf(TrackSpan(0, 500, 1_000)), 100))
    }

    @Test
    fun `in a gap the end of the previous track is used`() {
        val gap = listOf(TrackSpan(0, 0, 1_000), TrackSpan(1, 2_000, 1_000))
        assertEquals(TrackPosition(0, 1_000), locate(gap, 1_500))
        assertEquals(TrackPosition(1, 0), locate(gap, 2_000))
    }

    @Test
    fun `a single track covers its whole range`() {
        val one = listOf(TrackSpan(0, 0, 5_000))
        assertEquals(TrackPosition(0, 4_999), locate(one, 4_999))
        assertEquals(TrackPosition(0, 5_000), locate(one, 7_000))
    }

    @Test
    fun `book position adds the track start and clamps the offset to the track`() {
        assertEquals(1_250, bookPosition(three, 1, 250))
        assertEquals(3_000, bookPosition(three, 1, 9_999))
        assertEquals(1_000, bookPosition(three, 1, -5))
        assertEquals(3_500, bookPosition(three, 2, 500))
    }

    @Test
    fun `book position for an unknown track index is zero`() {
        assertEquals(0, bookPosition(three, 7, 100))
        assertEquals(0, bookPosition(three, -1, 100))
        assertEquals(0, bookPosition(emptyList(), 0, 100))
    }
}
