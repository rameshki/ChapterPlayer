package com.chapterplayer.domain.stats

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.stats.ImportOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsTextTest {

    private val today = epochDayOf(2026, 10, 26)

    @Test
    fun `listened time reads in hours and two digit minutes`() {
        assertEquals("0 m", formatListened(59))
        assertEquals("12 m", formatListened(12 * 60 + 30))
        assertEquals("3 h 02 m", formatListened(3 * 3_600 + 2 * 60))
        assertEquals("38 h 12 m", formatListened(38 * 3_600 + 12 * 60))
        assertEquals("0 m", formatListened(-5))
    }

    @Test
    fun `totals, time left and thousands`() {
        assertEquals("14,380 h", formatHoursTotal(14_380 * 3_600_000L))
        assertEquals("45 m", formatHoursTotal(45 * 60_000L))
        assertEquals("14 h left", formatTimeLeft(14 * 3_600_000L))
        assertEquals("2 h left", formatTimeLeft(90 * 60_000L))
        assertEquals("45 m left", formatTimeLeft(45 * 60_000L))
        assertEquals("1,204", groupThousands(1_204))
        assertEquals("999", groupThousands(999))
        assertEquals("1,000,000", groupThousands(1_000_000))
    }

    @Test
    fun `spoken durations`() {
        assertEquals("less than a minute", spokenDuration(30))
        assertEquals("1 minute", spokenDuration(60))
        assertEquals("2 hours", spokenDuration(7_200))
        assertEquals("1 hour 5 minutes", spokenDuration(3_900))
    }

    @Test
    fun `day labels`() {
        assertEquals("Mon 26 Oct", dayLabel(today))
        assertEquals("Sun 11 Oct", dayLabel(epochDayOf(2026, 10, 11)))
    }

    @Test
    fun `the hero detail gives the average and the change`() {
        val base = buildStats(StatsInput(today = today), StatsPeriod.MONTH)
        assertEquals("1 h 14 m a day on average · up 18% on last month", heroDetail(base.copy(averagePerDaySeconds = 74 * 60, changePercent = 18)))
        assertEquals("12 m a day on average · down 5% on last month", heroDetail(base.copy(averagePerDaySeconds = 12 * 60, changePercent = -5)))
        assertEquals("0 m a day on average · the same as last month", heroDetail(base.copy(changePercent = 0)))
        assertEquals("0 m a day on average", heroDetail(base))
    }

    @Test
    fun `source shares add up to a hundred, largest first`() {
        assertEquals(listOf(ServerSource.ABS to 67, ServerSource.PLEX to 33), sourceShares(mapOf(ServerSource.PLEX to 1L, ServerSource.ABS to 2L)))
        assertEquals(listOf(ServerSource.ABS to 100), sourceShares(mapOf(ServerSource.ABS to 5L, ServerSource.PLEX to 0L)))
        assertEquals(emptyList(), sourceShares(emptyMap()))
        assertEquals("Where you listened: Audiobookshelf 67%, Plex 33%", sourcesDescription(sourceShares(mapOf(ServerSource.PLEX to 1L, ServerSource.ABS to 2L))))
    }

    @Test
    fun `a tiny share reads as less than one percent, never zero`() {
        val shares = sourceShares(mapOf(ServerSource.ABS to 100_000L, ServerSource.PLEX to 10L))
        assertEquals(listOf(ServerSource.ABS to 100, ServerSource.PLEX to 0), shares)
        assertEquals("<1%", shareText(shares[1].second))
        assertEquals("Where you listened: Audiobookshelf 100%, Plex <1%", sourcesDescription(shares))
    }

    @Test
    fun `chart descriptions name the highest weekday and the busiest day`() {
        assertEquals("Listening by weekday: Saturday is highest, 2 hours", weekdayDescription(listOf(60L, 0, 0, 0, 0, 7_200, 3_600)))
        assertEquals("Listening by weekday: nothing yet", weekdayDescription(List(7) { 0L }))
        val grid = heatGrid(mapOf(today to DayTotal(3_720, false), today - 15 to DayTotal(600, false)), today, StatsPeriod.MONTH)
        assertEquals("Every day: listening on 2 of the last 92 days, most on Mon 26 Oct, 1 hour 2 minutes", heatDescription(grid))
        assertEquals("Every day: no listening in the last 1 days", heatDescription(heatGrid(emptyMap(), today, StatsPeriod.WEEK)))
    }

    @Test
    fun `series to finish line`() {
        assertEquals("3 of 7 finished · 14 h left", seriesToFinishLine(SeriesToFinish("k", "Saga", 3, 7, 14 * 3_600_000L)))
    }

    @Test
    fun `refresh outcome texts`() {
        assertEquals("History updated from 2 servers", importOutcomeText(ImportOutcome(imported = 2)))
        assertEquals("History updated from 1 server, could not reach 1 server", importOutcomeText(ImportOutcome(imported = 1, failed = 1)))
        assertEquals("Sign in again to update 1 server", importOutcomeText(ImportOutcome(signInRequired = 1)))
        assertEquals("No Audiobookshelf server to read listening history from", importOutcomeText(ImportOutcome()))
    }
}
