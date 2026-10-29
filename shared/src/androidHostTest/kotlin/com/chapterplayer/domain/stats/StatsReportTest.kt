package com.chapterplayer.domain.stats

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.stats.ImportedDay
import com.chapterplayer.data.stats.ImportedItem
import com.chapterplayer.data.stats.ListeningLogEntry
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsReportTest {

    // Monday 26 October 2026.
    private val today = epochDayOf(2026, 10, 26)

    private fun book(id: String, author: String = "Ann Lee") =
        AudioBook(id, "T $id", author, "", 36_000_000L, emptyList(), "", null, ServerSource.ABS, "s1")

    private fun entry(day: Int, book: String, wall: Long, bookSec: Long = wall) = ListeningLogEntry(day, book, "s1", ServerSource.ABS, wall, bookSec)

    @Test
    fun `no data at all is the empty state`() {
        val report = buildStats(StatsInput(today = today), StatsPeriod.MONTH)
        assertTrue(report.nothingYet)
        assertFalse(report.importedOnly)
        assertEquals(0L, report.listenedSeconds)
        assertNull(report.changePercent)
        assertNull(report.longestDay)
        assertEquals(0, report.streakDays)
        assertTrue(report.top.values.all { it.isEmpty() })
    }

    @Test
    fun `a single day of listening`() {
        val input = StatsInput(
            log = listOf(entry(today, "abs_a", 3_600, 5_400)),
            items = listOf(BrowseItem(book("abs_a"), null, DownloadState.NOT_DOWNLOADED)),
            today = today,
        )
        val week = buildStats(input, StatsPeriod.WEEK)
        assertEquals(DayRange(today, today), week.range)
        assertEquals(3_600L, week.listenedSeconds)
        assertEquals(3_600L, week.averagePerDaySeconds)
        assertNull(week.changePercent)
        assertEquals(1, week.streakDays)
        assertEquals(1_800L, week.timeSavedSeconds)
        assertEquals(today to 3_600L, week.longestDay)
        assertEquals(listOf(Ranked("ann lee", "Ann Lee", 3_600)), week.top.getValue(TopKind.AUTHORS))
        assertEquals(mapOf(ServerSource.ABS to 3_600L), week.sources)
        assertFalse(week.nothingYet)
        // All time starts at the first day listened.
        assertEquals(DayRange(today, today), buildStats(input, StatsPeriod.ALL_TIME).range)
    }

    @Test
    fun `the month compares its daily average with last month`() {
        val input = StatsInput(
            // 26 days of October so far: 26 h. September: 15 h over 30 days.
            log = (0 until 26).map { entry(epochDayOf(2026, 10, 1) + it, "abs_a", 3_600) } +
                (0 until 15).map { entry(epochDayOf(2026, 9, 1) + it, "abs_a", 3_600) },
            today = today,
        )
        val month = buildStats(input, StatsPeriod.MONTH)
        assertEquals(26 * 3_600L, month.listenedSeconds)
        assertEquals(3_600L, month.averagePerDaySeconds)
        assertEquals(100, month.changePercent)
        assertEquals(26, month.streakDays)
    }

    @Test
    fun `a period with only imported history is marked and all time adds the server's book totals`() {
        val input = StatsInput(
            log = listOf(entry(today - 40, "abs_a", 600)),
            importedDays = listOf(ImportedDay("s1", today - 1, 1_200), ImportedDay("s1", today - 40, 50)),
            importedItems = listOf(ImportedItem("s1", "abs_a", 3_000, loggedAtImport = 600)),
            items = listOf(BrowseItem(book("abs_a"), null, DownloadState.NOT_DOWNLOADED)),
            today = today,
        )
        val week = buildStats(input, StatsPeriod.WEEK)
        // Monday: the week is only today, which has nothing.
        assertEquals(0L, week.listenedSeconds)
        assertFalse(week.importedOnly)
        val month = buildStats(input, StatsPeriod.MONTH)
        assertEquals(1_200L, month.listenedSeconds)
        assertTrue(month.importedOnly)
        assertTrue(month.top.getValue(TopKind.AUTHORS).isEmpty())
        val all = buildStats(input, StatsPeriod.ALL_TIME)
        assertFalse(all.importedOnly)
        assertEquals(1_800L, all.listenedSeconds)
        assertEquals(listOf(Ranked("ann lee", "Ann Lee", 3_000)), all.top.getValue(TopKind.AUTHORS))
    }

    @Test
    fun `finished books follow the period and all time counts every one`() {
        val finishedLongAgo = BrowseItem(book("abs_old"), ReadingProgress("abs_old", 10, 36_000_000L, (today - 400) * MS_PER_DAY, true), DownloadState.NOT_DOWNLOADED)
        val finishedToday = BrowseItem(book("abs_new"), ReadingProgress("abs_new", 10, 36_000_000L, today * MS_PER_DAY + 1, true), DownloadState.NOT_DOWNLOADED)
        val input = StatsInput(items = listOf(finishedLongAgo, finishedToday), today = today)
        assertEquals(1, buildStats(input, StatsPeriod.YEAR).booksFinished)
        assertEquals(2, buildStats(input, StatsPeriod.ALL_TIME).booksFinished)
    }
}
