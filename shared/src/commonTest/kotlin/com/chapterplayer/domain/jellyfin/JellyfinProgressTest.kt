package com.chapterplayer.domain.jellyfin

import com.chapterplayer.data.remote.jellyfin.dto.JellyfinConfigurationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinProgressTest {

    private val defaults = AudiobookThresholds()
    private val hour = 60 * 60_000L
    private val book = 10 * hour
    private val now = 1_800_000_000_000L

    private fun ms(min: Int, sec: Int) = min * 60_000L + sec * 1_000L

    @Test
    fun `the start band ends at five minutes`() {
        assertTrue(inThresholdBand(ms(4, 59), book, defaults))
        assertFalse(inThresholdBand(ms(5, 0), book, defaults))
        assertTrue(inThresholdBand(0L, book, defaults))
    }

    @Test
    fun `the end band starts with less than five minutes left`() {
        assertFalse(inThresholdBand(book - ms(5, 0), book, defaults))
        assertTrue(inThresholdBand(book - ms(4, 59), book, defaults))
        assertTrue(inThresholdBand(book, book, defaults))
    }

    @Test
    fun `a three minute book is all band`() {
        val short = ms(3, 0)
        for (p in listOf(0L, ms(1, 0), ms(1, 30), ms(2, 59), short)) assertTrue(inThresholdBand(p, short, defaults), "$p")
    }

    @Test
    fun `the middle of the book is outside the band`() {
        assertFalse(inThresholdBand(hour, book, defaults))
    }

    @Test
    fun `other thresholds move the band`() {
        val t = AudiobookThresholds(minResumeMin = 2, maxResumeMin = 10)
        assertFalse(inThresholdBand(ms(2, 0), book, t))
        assertTrue(inThresholdBand(ms(1, 59), book, t))
        assertTrue(inThresholdBand(book - ms(9, 59), book, t))
        assertFalse(inThresholdBand(book - ms(10, 0), book, t))
    }

    @Test
    fun `start outside a band is the start report only`() {
        assertEquals(
            listOf(JellyfinCall.SessionStart(hour)),
            jellyfinLivePlan(JellyfinEvent.START, hour, book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `start in a band adds the user data write`() {
        assertEquals(
            listOf(JellyfinCall.SessionStart(ms(2, 0)), JellyfinCall.WriteUserData(ms(2, 0), false, now)),
            jellyfinLivePlan(JellyfinEvent.START, ms(2, 0), book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `playing outside a band is the progress report only`() {
        assertEquals(
            listOf(JellyfinCall.SessionProgress(hour, paused = false)),
            jellyfinLivePlan(JellyfinEvent.PLAYING, hour, book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `playing in either band adds the user data write`() {
        assertEquals(
            listOf(JellyfinCall.SessionProgress(ms(2, 0), paused = false), JellyfinCall.WriteUserData(ms(2, 0), false, now)),
            jellyfinLivePlan(JellyfinEvent.PLAYING, ms(2, 0), book, now, defaults, markedFinished = false),
        )
        val nearEnd = book - ms(2, 0)
        assertEquals(
            listOf(JellyfinCall.SessionProgress(nearEnd, paused = false), JellyfinCall.WriteUserData(nearEnd, false, now)),
            jellyfinLivePlan(JellyfinEvent.PLAYING, nearEnd, book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `a pause always writes the user data`() {
        assertEquals(
            listOf(JellyfinCall.SessionProgress(hour, paused = true), JellyfinCall.WriteUserData(hour, false, now)),
            jellyfinLivePlan(JellyfinEvent.PAUSED, hour, book, now, defaults, markedFinished = false),
        )
        assertEquals(
            listOf(JellyfinCall.SessionProgress(ms(1, 0), paused = true), JellyfinCall.WriteUserData(ms(1, 0), false, now)),
            jellyfinLivePlan(JellyfinEvent.PAUSED, ms(1, 0), book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `closing outside a band is the stop report only`() {
        assertEquals(
            listOf(JellyfinCall.SessionStopped(hour)),
            jellyfinLivePlan(JellyfinEvent.CLOSED, hour, book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `closing in a band adds the user data write after the stop`() {
        val nearEnd = book - ms(1, 0)
        assertEquals(
            listOf(JellyfinCall.SessionStopped(nearEnd), JellyfinCall.WriteUserData(nearEnd, false, now)),
            jellyfinLivePlan(JellyfinEvent.CLOSED, nearEnd, book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `the real end stops at the end and writes nothing`() {
        assertEquals(
            listOf(JellyfinCall.SessionStopped(book)),
            jellyfinLivePlan(JellyfinEvent.ENDED, book - 500L, book, now, defaults, markedFinished = false),
        )
    }

    @Test
    fun `no stop without a position`() {
        for (event in JellyfinEvent.entries) {
            for (p in listOf(0L, ms(2, 0), hour, book - ms(1, 0), book)) {
                jellyfinLivePlan(event, p, book, now, defaults, markedFinished = false)
                    .filterIsInstance<JellyfinCall.SessionStopped>()
                    .forEach { assertTrue(it.positionMs >= 0L, "$event $p") }
            }
        }
        // Closing at the very start still carries its position (0), never a stop without one.
        assertEquals(
            JellyfinCall.SessionStopped(0L),
            jellyfinLivePlan(JellyfinEvent.CLOSED, 0L, book, now, defaults, markedFinished = false).first(),
        )
    }

    @Test
    fun `a finished pin sends nothing`() {
        for (event in JellyfinEvent.entries) {
            assertEquals(emptyList(), jellyfinLivePlan(event, ms(2, 0), book, now, defaults, markedFinished = true), "$event")
            assertEquals(emptyList(), jellyfinLivePlan(event, hour, book, now, defaults, markedFinished = true), "$event")
        }
    }

    @Test
    fun `the push of a finished book marks it played`() {
        assertEquals(listOf(JellyfinCall.MarkPlayed), jellyfinPushPlan(book, isFinished = true, playedAtMs = now))
    }

    @Test
    fun `the push of an unfinished book writes the position at its time`() {
        assertEquals(
            listOf(JellyfinCall.WriteUserData(ms(2, 0), false, now - 5_000L)),
            jellyfinPushPlan(ms(2, 0), isFinished = false, playedAtMs = now - 5_000L),
        )
        assertEquals(listOf(JellyfinCall.WriteUserData(0L, false, now)), jellyfinPushPlan(0L, isFinished = false, playedAtMs = now))
    }

    @Test
    fun `thresholds come from the server configuration`() {
        assertEquals(
            AudiobookThresholds(2, 10),
            thresholdsOf(JellyfinConfigurationDto(minAudiobookResume = 2, maxAudiobookResume = 10)),
        )
    }

    @Test
    fun `missing or odd thresholds read as five and five`() {
        assertEquals(AudiobookThresholds(5, 5), thresholdsOf(JellyfinConfigurationDto()))
        assertEquals(AudiobookThresholds(5, 5), thresholdsOf(null))
        assertEquals(AudiobookThresholds(5, 5), thresholdsOf(JellyfinConfigurationDto(minAudiobookResume = 0, maxAudiobookResume = -3)))
        assertEquals(AudiobookThresholds(5, 7), thresholdsOf(JellyfinConfigurationDto(minAudiobookResume = null, maxAudiobookResume = 7)))
    }
}
