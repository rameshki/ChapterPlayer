package com.chapterplayer.domain.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesNameTest {

    @Test
    fun `a name with a number after a hash is split into name and sequence`() {
        assertEquals(ParsedSeries("Fixture Saga", "25"), parseSeriesName("Fixture Saga #25"))
    }

    @Test
    fun `a decimal sequence is kept as written`() {
        assertEquals(ParsedSeries("Saga", "3.5"), parseSeriesName("Saga #3.5"))
        assertEquals(ParsedSeries("Saga", "03"), parseSeriesName("Saga #03"))
    }

    @Test
    fun `extra spaces around the name and the number are trimmed`() {
        assertEquals(ParsedSeries("Saga", "4"), parseSeriesName("  Saga  #  4 "))
    }

    @Test
    fun `a name without a hash has no sequence`() {
        assertEquals(ParsedSeries("Saga", null), parseSeriesName("Saga"))
        assertEquals(ParsedSeries("Saga of Ice", null), parseSeriesName("  Saga of Ice  "))
    }

    @Test
    fun `a hash that is not followed by a number stays part of the name`() {
        assertEquals(ParsedSeries("Saga #x", null), parseSeriesName("Saga #x"))
        assertEquals(ParsedSeries("Saga #", null), parseSeriesName("Saga #"))
        assertEquals(ParsedSeries("Saga #3x", null), parseSeriesName("Saga #3x"))
    }

    @Test
    fun `a string that is only a hash and a number keeps everything as the name`() {
        assertEquals(ParsedSeries("#5", null), parseSeriesName("#5"))
    }

    @Test
    fun `only the last hash number is the sequence`() {
        assertEquals(ParsedSeries("Book #1 Edition", "2"), parseSeriesName("Book #1 Edition #2"))
    }

    @Test
    fun `a book in two series belongs to the first one only`() {
        assertEquals(ParsedSeries("Saga A", "1"), parseSeriesName("Saga A #1, Saga B #2"))
    }

    @Test
    fun `a book in three series belongs to the first one only`() {
        assertEquals(ParsedSeries("Saga A", "1"), parseSeriesName("Saga A #1, Saga B #2, Saga C #3"))
    }

    @Test
    fun `a decimal sequence before the next series is kept`() {
        assertEquals(ParsedSeries("Saga A", "1.5"), parseSeriesName("Saga A #1.5, Saga B #2"))
    }

    @Test
    fun `a first series without a number still ends at the second series`() {
        assertEquals(ParsedSeries("Saga A", "1"), parseSeriesName("Saga A #1, Saga B"))
    }

    @Test
    fun `a comma inside a name without a number stays in the name`() {
        assertEquals(ParsedSeries("Saga A, Saga B", null), parseSeriesName("Saga A, Saga B"))
        assertEquals(ParsedSeries("Saga A, Part Two", "4"), parseSeriesName("Saga A, Part Two #4"))
    }

    @Test
    fun `null and blank input give no series`() {
        assertNull(parseSeriesName(null))
        assertNull(parseSeriesName(""))
        assertNull(parseSeriesName("   "))
    }
}
