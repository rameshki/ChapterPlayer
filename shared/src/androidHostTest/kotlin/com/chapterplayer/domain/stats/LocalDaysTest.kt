package com.chapterplayer.domain.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalDaysTest {

    private val hour = 3_600_000L

    @Test
    fun `the epoch day of an instant in UTC`() {
        assertEquals(0, epochDayOf(0L, DayZone.UTC))
        assertEquals(0, epochDayOf(MS_PER_DAY - 1, DayZone.UTC))
        assertEquals(1, epochDayOf(MS_PER_DAY, DayZone.UTC))
        assertEquals(-1, epochDayOf(-1L, DayZone.UTC))
    }

    @Test
    fun `a zone east of UTC starts the day earlier`() {
        val berlinSummer = DayZone { 2 * hour }
        // 23:00 UTC on day 0 is 01:00 on day 1 in the zone.
        assertEquals(1, epochDayOf(23 * hour, berlinSummer))
        assertEquals(0, epochDayOf(21 * hour + 59 * 60_000L, berlinSummer))
    }

    @Test
    fun `a zone west of UTC keeps the late evening on the earlier day`() {
        val newYork = DayZone { -5 * hour }
        assertEquals(0, epochDayOf(MS_PER_DAY + 4 * hour, newYork))
        assertEquals(1, epochDayOf(MS_PER_DAY + 5 * hour, newYork))
    }

    @Test
    fun `the day boundary follows the offset in force at each instant across a DST change`() {
        // Clocks go forward one hour at 01:00 UTC on day 100 (offset +1 h before, +2 h after).
        val change = 100 * MS_PER_DAY + 1 * hour
        val zone = DayZone { at -> if (at < change) hour else 2 * hour }
        // 22:59 UTC on day 99 is 23:59 local on day 99; 23:00 UTC is 00:00 local on day 100.
        assertEquals(99, epochDayOf(100 * MS_PER_DAY - hour - 60_000L, zone))
        assertEquals(100, epochDayOf(100 * MS_PER_DAY - hour, zone))
        // After the change local midnight of day 101 is 22:00 UTC on day 100.
        assertEquals(100, epochDayOf(101 * MS_PER_DAY - 2 * hour - 1, zone))
        assertEquals(101, epochDayOf(101 * MS_PER_DAY - 2 * hour, zone))
    }

    @Test
    fun `civil dates round trip`() {
        assertEquals(CivilDate(1970, 1, 1), civilDate(0))
        assertEquals(CivilDate(2000, 2, 29), civilDate(epochDayOf(2000, 2, 29)))
        assertEquals(CivilDate(2026, 10, 26), civilDate(20_752))
        assertEquals(20_752, epochDayOf(2026, 10, 26))
        assertEquals(CivilDate(1969, 12, 31), civilDate(-1))
        for (day in -800..30_000 step 37) assertEquals(day, civilDate(day).let { epochDayOf(it.year, it.month, it.day) })
    }

    @Test
    fun `weekday numbers start at Monday`() {
        // 1970-01-01 was a Thursday; 2026-10-26 is a Monday.
        assertEquals(3, weekdayOf(0))
        assertEquals(0, weekdayOf(20_752))
        assertEquals(6, weekdayOf(20_758))
        assertEquals(2, weekdayOf(-1))
    }

    @Test
    fun `days in a month know leap years`() {
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(28, daysInMonth(1900, 2))
        assertEquals(29, daysInMonth(2000, 2))
        assertEquals(31, daysInMonth(2026, 12))
        assertEquals(30, daysInMonth(2026, 11))
    }

    @Test
    fun `an ISO day parses and anything else does not`() {
        assertEquals(20_752, parseIsoDay("2026-10-26"))
        assertEquals(epochDayOf(2024, 2, 29), parseIsoDay("2024-02-29"))
        assertNull(parseIsoDay("2023-02-29"))
        assertNull(parseIsoDay("2026-13-01"))
        assertNull(parseIsoDay("26-10-2026"))
        assertNull(parseIsoDay(""))
        assertNull(parseIsoDay("2026-10-26T10:00"))
    }
}
